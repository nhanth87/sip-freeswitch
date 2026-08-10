package et.elisa.ims.scscf;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.sipservlet.command.SendBye;
import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.command.SendSdpUpdate;
import com.microjainslee.ra.sipservlet.command.SipOutboundCommand;
import com.microjainslee.ra.sipservlet.events.SipByeEvent;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipResponseEvent;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import et.elisa.ims.charging.PChargingVectors;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.metrics.SipCpsMetrics;
import et.elisa.persist.ImsBinding;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * S-CSCF session — ISC to FreeSWITCH AS/MRF; MT via binding contact.
 * <p>
 * JAIN SLEE 1.1 §6.5 CMP fields recover app call state after SBB entity death
 * via {@link DialogCmpPersistence} (ISPN → PG). SendResponse/SendInvite still
 * require RA {@code DialogRegistry} (JVM-local) — see docs/agents/cmp-recovery.md.
 */
@SbbAnnotation(name = "ScscfInviteSbb", vendor = "et.elisa", version = "1.0")
public abstract class ScscfInviteSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(ScscfInviteSbb.class);

    private final ImsRoleFlags roles;
    private final IscAsRouter isc;
    private final BindingStore bindings;
    private final DialogCmpPersistence cmpStore;
    private final SipCpsMetrics metrics;
    private final HssLiteService hss;
    private final String nodeId;
    private final String fsNodeId;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public ScscfInviteSbb() {
        this(null, null, null, null, null, null, "elisa-1", "fallback");
    }

    public ScscfInviteSbb(ImsRoleFlags roles, IscAsRouter isc, BindingStore bindings,
                          DialogCmpPersistence cmpStore, SipCpsMetrics metrics, HssLiteService hss,
                          String nodeId, String fsNodeId) {
        this.roles = roles;
        this.isc = isc;
        this.bindings = bindings;
        this.cmpStore = cmpStore;
        this.metrics = metrics;
        this.hss = hss;
        this.nodeId = nodeId;
        this.fsNodeId = fsNodeId;
    }

    // ── JAIN SLEE 1.1 §6.5 CMP ──────────────────────────────────────────

    @CmpField("sipCallId")
    public abstract String getSipCallId();

    @CmpField("sipCallId")
    public abstract void setSipCallId(String sipCallId);

    @CmpField("fsUuid")
    public abstract String getFsUuid();

    @CmpField("fsUuid")
    public abstract void setFsUuid(String fsUuid);

    @CmpField("msisdn")
    public abstract String getMsisdn();

    @CmpField("msisdn")
    public abstract void setMsisdn(String msisdn);

    @CmpField("fromUri")
    public abstract String getFromUri();

    @CmpField("fromUri")
    public abstract void setFromUri(String fromUri);

    @CmpField("toUri")
    public abstract String getToUri();

    @CmpField("toUri")
    public abstract void setToUri(String toUri);

    @CmpField("trunkUri")
    public abstract String getTrunkUri();

    @CmpField("trunkUri")
    public abstract void setTrunkUri(String trunkUri);

    @CmpField("peer")
    public abstract String getPeer();

    @CmpField("peer")
    public abstract void setPeer(String peer);

    @CmpField("fsNodeId")
    public abstract String getFsNodeId();

    @CmpField("fsNodeId")
    public abstract void setFsNodeId(String fsNodeId);

    @CmpField("elisaNodeId")
    public abstract String getElisaNodeId();

    @CmpField("elisaNodeId")
    public abstract void setElisaNodeId(String elisaNodeId);

    @CmpField("dialogState")
    public abstract String getDialogState();

    @CmpField("dialogState")
    public abstract void setDialogState(String dialogState);

    @CmpField("startedAtEpochMs")
    public abstract Long getStartedAtEpochMs();

    @CmpField("startedAtEpochMs")
    public abstract void setStartedAtEpochMs(Long startedAtEpochMs);

    @CmpField("updatedAtEpochMs")
    public abstract Long getUpdatedAtEpochMs();

    @CmpField("updatedAtEpochMs")
    public abstract void setUpdatedAtEpochMs(Long updatedAtEpochMs);

    @Override
    public void sbbCreate() {
        // Entity id bound by container before this; CMP empty until INVITE or restore.
    }

    @Override
    public void sbbActivate() {
        // If entity was passivated with CMP in container store, CmpAccessorInvoker
        // already holds fields. Cluster recovery: restore by sipCallId if set.
        String callId = getSipCallId();
        if (callId != null && !callId.isBlank()) {
            restoreCmp(callId);
        }
    }

    @Override
    public void sbbPassivate() {
        flushCmp();
    }

    @Override
    public void sbbRemove() {
        String callId = getSipCallId();
        if (callId != null && cmpStore != null) {
            cmpStore.remove(callId);
        }
    }

    @Override
    public void sbbStore() {
        cmpPersist();
        flushCmp();
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case SipInviteEvent e -> onInvite(e);
            case SipByeEvent e -> onBye(e);
            case SipResponseEvent e -> onResponse(e);
            default -> {}
        }
    }

    void onInvite(SipInviteEvent e) {
        if (roles == null || !roles.scscf()) {
            return;
        }
        if (metrics != null) {
            metrics.onInvite();
        }
        String to = e.toUri();
        boolean terminating = bindings != null && bindings.findLive(to).isPresent();
        String target;
        if (terminating) {
            ImsBinding b = bindings.findLive(to).orElseThrow();
            target = b.contact;
        } else {
            String ifc = hss == null ? null : hss.findByImpu(e.fromUri()).map(s -> s.ifcJson).orElse(null);
            if (isc != null && isc.shouldInvokeAs(ifc)) {
                target = isc.asUri();
            } else {
                // iFC skip / no AS → route toward Request-URI (callee), not force AS
                target = to;
            }
        }
        String msisdn = hss == null ? null
                : hss.resolveIdentity(e.fromUri()).map(s -> s.msisdn != null ? s.msisdn : s.directoryUserId())
                .or(() -> hss.resolveIdentity(e.toUri()).map(s -> s.msisdn != null ? s.msisdn : s.directoryUserId()))
                .orElse(null);
        long now = Instant.now().toEpochMilli();
        applyCmp(new InviteCmpSnapshot(
                e.callId(), null, msisdn, e.fromUri(), e.toUri(), target, null,
                fsNodeId, nodeId, DialogCheckpoint.STATE_INVITE, now, now));
        flushCmp();
        LOG.debug("[S-CSCF] INVITE callId={} target={} terminating={}", e.callId(), target, terminating);
        Map<String, java.util.List<String>> ims = PChargingVectors.copyIms(e.imsHeaders());
        if (!ims.containsKey("P-Asserted-Identity") && e.fromUri() != null) {
            ims.put("P-Asserted-Identity", java.util.List.of(e.fromUri()));
        }
        ims.put("X-Elisa-Sip-Call-Id", java.util.List.of(e.callId()));
        String realm = roles.realm() != null ? roles.realm() : "ims.lab";
        PChargingVectors.ensureInvite(ims, e.callId(), realm);
        if (!terminating) {
            ims.put("Route", java.util.List.of("<" + target + ";lr>"));
        }
        send(new SendInvite(e.callId(), target, e.fromUri(), e.sdpBody(), ims));
    }

    void onBye(SipByeEvent e) {
        ensureCmpLoaded(e.callId());
        if (metrics != null) {
            metrics.onDialogEnd();
        }
        setDialogState(DialogCheckpoint.STATE_TERMINATED);
        setUpdatedAtEpochMs(Instant.now().toEpochMilli());
        if (cmpStore != null) {
            cmpStore.remove(e.callId());
        }
        clearLocalCmp();
        send(new SendBye(e.callId()));
        send(new SendResponse(e.callId(), 200, "OK"));
    }

    void onResponse(SipResponseEvent e) {
        ensureCmpLoaded(e.callId());
        if (e.isProvisional()) {
            if (getDialogState() == null || DialogCheckpoint.STATE_INVITE.equals(getDialogState())) {
                setDialogState(DialogCheckpoint.STATE_EARLY);
                setUpdatedAtEpochMs(Instant.now().toEpochMilli());
                flushCmp();
            }
            send(new SendResponse(e.callId(), e.statusCode(), e.reasonPhrase()));
        } else if (e.isSuccess() && e.sdpBody() != null && !e.sdpBody().isEmpty()) {
            setDialogState(DialogCheckpoint.STATE_CONFIRMED);
            setUpdatedAtEpochMs(Instant.now().toEpochMilli());
            flushCmp();
            send(new SendSdpUpdate(e.callId(), e.sdpBody()));
        } else if (e.isFinal()) {
            if (e.statusCode() >= 400) {
                setDialogState(DialogCheckpoint.STATE_TERMINATED);
                if (cmpStore != null) {
                    cmpStore.remove(e.callId());
                }
                clearLocalCmp();
            } else if (e.isSuccess()) {
                setDialogState(DialogCheckpoint.STATE_CONFIRMED);
                setUpdatedAtEpochMs(Instant.now().toEpochMilli());
                flushCmp();
            }
            send(new SendResponse(e.callId(), e.statusCode(), e.reasonPhrase()));
        }
    }

    /**
     * Simulate SBB death → recreate: wipe local CMP and reload from ISPN/PG.
     * @return true when a live checkpoint was restored
     */
    public boolean recoverAfterFailure(String sipCallId) {
        clearLocalCmp();
        return restoreCmp(sipCallId);
    }

    /** @return true when CMP was populated from durable store */
    public boolean restoreCmp(String sipCallId) {
        if (cmpStore == null || sipCallId == null) {
            return false;
        }
        return cmpStore.load(sipCallId).map(snap -> {
            applyCmp(snap);
            LOG.debug("[S-CSCF] CMP restored callId={} state={}", sipCallId, snap.dialogState());
            return true;
        }).orElse(false);
    }

    public InviteCmpSnapshot snapshotCmp() {
        Long started = getStartedAtEpochMs();
        Long updated = getUpdatedAtEpochMs();
        return new InviteCmpSnapshot(
                getSipCallId(),
                getFsUuid(),
                getMsisdn(),
                getFromUri(),
                getToUri(),
                getTrunkUri(),
                getPeer(),
                getFsNodeId(),
                getElisaNodeId(),
                getDialogState(),
                started == null ? 0L : started,
                updated == null ? 0L : updated);
    }

    void linkFsUuid(String fsUuid, String msisdn) {
        if (fsUuid == null) {
            return;
        }
        setFsUuid(fsUuid);
        if (msisdn != null) {
            setMsisdn(msisdn);
        }
        setUpdatedAtEpochMs(Instant.now().toEpochMilli());
        flushCmp();
    }

    private void ensureCmpLoaded(String callId) {
        if (callId == null) {
            return;
        }
        String current = getSipCallId();
        if (current == null || current.isBlank() || !callId.equals(current)) {
            restoreCmp(callId);
        }
    }

    private void applyCmp(InviteCmpSnapshot snap) {
        if (snap == null) {
            return;
        }
        setSipCallId(snap.sipCallId());
        setFsUuid(snap.fsUuid());
        setMsisdn(snap.msisdn());
        setFromUri(snap.fromUri());
        setToUri(snap.toUri());
        setTrunkUri(snap.trunkUri());
        setPeer(snap.peer());
        setFsNodeId(snap.fsNodeId() != null ? snap.fsNodeId() : fsNodeId);
        setElisaNodeId(snap.elisaNodeId() != null ? snap.elisaNodeId() : nodeId);
        setDialogState(snap.dialogState());
        setStartedAtEpochMs(snap.startedAtEpochMs());
        setUpdatedAtEpochMs(snap.updatedAtEpochMs());
    }

    private void flushCmp() {
        if (cmpStore == null) {
            return;
        }
        String callId = getSipCallId();
        if (callId == null || callId.isBlank()) {
            return;
        }
        cmpStore.save(snapshotCmp());
    }

    private void clearLocalCmp() {
        setSipCallId(null);
        setFsUuid(null);
        setMsisdn(null);
        setFromUri(null);
        setToUri(null);
        setTrunkUri(null);
        setPeer(null);
        setFsNodeId(null);
        setElisaNodeId(null);
        setDialogState(null);
        setStartedAtEpochMs(null);
        setUpdatedAtEpochMs(null);
    }

    private void send(SipOutboundCommand cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }

    // ── $Concrete (micro-jainslee CMP pattern) ──────────────────────────

    public static final class $Concrete extends ScscfInviteSbb {

        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(ImsRoleFlags roles, IscAsRouter isc, BindingStore bindings,
                         DialogCmpPersistence cmpStore, SipCpsMetrics metrics, HssLiteService hss,
                         String nodeId, String fsNodeId) {
            super(roles, isc, bindings, cmpStore, metrics, hss, nodeId, fsNodeId);
        }

        @Override public String getSipCallId() { return str("sipCallId"); }
        @Override public void setSipCallId(String v) { put("sipCallId", v); write("setSipCallId", String.class, v); }

        @Override public String getFsUuid() { return str("fsUuid"); }
        @Override public void setFsUuid(String v) { put("fsUuid", v); write("setFsUuid", String.class, v); }

        @Override public String getMsisdn() { return str("msisdn"); }
        @Override public void setMsisdn(String v) { put("msisdn", v); write("setMsisdn", String.class, v); }

        @Override public String getFromUri() { return str("fromUri"); }
        @Override public void setFromUri(String v) { put("fromUri", v); write("setFromUri", String.class, v); }

        @Override public String getToUri() { return str("toUri"); }
        @Override public void setToUri(String v) { put("toUri", v); write("setToUri", String.class, v); }

        @Override public String getTrunkUri() { return str("trunkUri"); }
        @Override public void setTrunkUri(String v) { put("trunkUri", v); write("setTrunkUri", String.class, v); }

        @Override public String getPeer() { return str("peer"); }
        @Override public void setPeer(String v) { put("peer", v); write("setPeer", String.class, v); }

        @Override public String getFsNodeId() { return str("fsNodeId"); }
        @Override public void setFsNodeId(String v) { put("fsNodeId", v); write("setFsNodeId", String.class, v); }

        @Override public String getElisaNodeId() { return str("elisaNodeId"); }
        @Override public void setElisaNodeId(String v) { put("elisaNodeId", v); write("setElisaNodeId", String.class, v); }

        @Override public String getDialogState() { return str("dialogState"); }
        @Override public void setDialogState(String v) { put("dialogState", v); write("setDialogState", String.class, v); }

        @Override public Long getStartedAtEpochMs() { return lng("startedAtEpochMs"); }
        @Override public void setStartedAtEpochMs(Long v) { put("startedAtEpochMs", v); write("setStartedAtEpochMs", Long.class, v); }

        @Override public Long getUpdatedAtEpochMs() { return lng("updatedAtEpochMs"); }
        @Override public void setUpdatedAtEpochMs(Long v) { put("updatedAtEpochMs", v); write("setUpdatedAtEpochMs", Long.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            if (v instanceof String s) {
                return s;
            }
            try {
                Object fromStore = cmpRead(method("get" + cap(key)));
                return fromStore instanceof String s ? s : null;
            } catch (IllegalStateException unbound) {
                return null;
            }
        }

        private Long lng(String key) {
            Object v = local.get(key);
            if (v instanceof Long l) {
                return l;
            }
            try {
                Object fromStore = cmpRead(method("get" + cap(key)));
                return fromStore instanceof Long l ? l : null;
            } catch (IllegalStateException unbound) {
                return null;
            }
        }

        private void put(String key, Object value) {
            if (value == null) {
                local.remove(key);
            } else {
                local.put(key, value);
            }
        }

        private void write(String setter, Class<?> type, Object value) {
            try {
                cmpWrite(method(setter, type), value);
            } catch (IllegalStateException unbound) {
                // Unit tests / unbound entity — local map is enough; dual store via DialogCmpPersistence.
            }
        }

        private static String cap(String field) {
            return Character.toUpperCase(field.charAt(0)) + field.substring(1);
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return ScscfInviteSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
