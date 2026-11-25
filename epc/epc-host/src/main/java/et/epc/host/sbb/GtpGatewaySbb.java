package et.epc.host.sbb;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.GtpEchoCommand;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.GtpEchoEvent;
import com.microjainslee.ra.gtpv2c.event.GtpProcedureTimeoutEvent;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.event.PfcpAssociationEvent;
import com.microjainslee.ra.pfcp.event.PfcpHeartbeatEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.pgwc.CreateSessionResult;
import et.epc.pgwc.PgwControl;
import et.epc.profile.ApnProfile;
import et.epc.profile.EpcProfiles;
import et.epc.sgwc.GtpcShapes;
import et.epc.sgwc.SgwControl;
import et.epc.sgwc.SgwCreateSessionResult;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * GTP-C / PFCP application SBB. UDP, Echo, Association stay on {@code gtpv2c-ra}
 * / {@code pfcp-ra}. CMP holds the live TEID/SEID correlate; Profile is APN only.
 */
@SbbAnnotation(name = "GtpGatewaySbb", vendor = "et.epc", version = "1.0")
public abstract class GtpGatewaySbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(GtpGatewaySbb.class);

    private final SgwControl sgw;
    private final PgwControl pgw;
    private final ProfileFacility profiles;

    @InjectRa(name = "gtpv2c-ra")
    private volatile RaCommandPort gtpRa;

    @InjectRa(name = "pfcp-ra")
    private volatile RaCommandPort pfcpRa;

    public GtpGatewaySbb() {
        this(null, null, null);
    }

    public GtpGatewaySbb(SgwControl sgw, PgwControl pgw, ProfileFacility profiles) {
        this.sgw = sgw;
        this.pgw = pgw;
        this.profiles = profiles;
    }

    /** PGW-C S5-C create with Gx in between (two-phase); null = one-step create. */
    private volatile PgwS5cProcedure pgwProcedure;

    public GtpGatewaySbb withPgwProcedure(PgwS5cProcedure procedure) {
        this.pgwProcedure = procedure;
        return this;
    }

    @CmpField("imsi")
    public abstract String getImsi();

    @CmpField("imsi")
    public abstract void setImsi(String imsi);

    @CmpField("apn")
    public abstract String getApn();

    @CmpField("apn")
    public abstract void setApn(String apn);

    @CmpField("s11cTeid")
    public abstract Integer getS11cTeid();

    @CmpField("s11cTeid")
    public abstract void setS11cTeid(Integer teid);

    @CmpField("pfcpSeid")
    public abstract Long getPfcpSeid();

    @CmpField("pfcpSeid")
    public abstract void setPfcpSeid(Long seid);

    @CmpField("generation")
    public abstract Long getGeneration();

    @CmpField("generation")
    public abstract void setGeneration(Long generation);

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case Gtpv2MessageEvent gtp -> onGtp(gtp);
            case GtpEchoEvent echo -> onGtpEcho(echo);
            case GtpProcedureTimeoutEvent timeout -> onGtpTimeout(timeout);
            case PfcpMessageEvent pfcp -> onPfcp(pfcp);
            case PfcpAssociationEvent assoc -> onPfcpAssociation(assoc);
            case PfcpHeartbeatEvent hb -> onPfcpHeartbeat(hb);
            default -> {}
        }
    }

    private void onGtp(Gtpv2MessageEvent ev) {
        Gtpv2Message msg = ev.message();
        try {
            switch (msg.type()) {
                case CREATE_SESSION_REQUEST -> createSession(ev);
                case CREATE_SESSION_RESPONSE -> s5cResponse(ev);
                case DELETE_SESSION_RESPONSE -> s5cDeleteResponse(ev);
                case MODIFY_BEARER_REQUEST -> modifyBearer(ev);
                case DELETE_SESSION_REQUEST -> deleteSession(ev);
                case RELEASE_ACCESS_BEARERS_REQUEST -> releaseAccessBearers(ev);
                case DOWNLINK_DATA_NOTIFICATION -> downlinkDataNotification(ev);
                case DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE ->
                    LOG.debug("GtpGatewaySbb DDN-Ack seq={}", msg.sequence());
                default -> LOG.debug("GtpGatewaySbb ignore {}", msg.type());
            }
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            LOG.warn("GtpGatewaySbb {} failed: {}{}", msg.type(), e.toString(),
                    root == e ? "" : " (root: " + root + ")");
            if (msg.type().isRequest()) {
                send(error(msg, 73), ev.peer());
            }
        }
    }

    private void onPfcp(PfcpMessageEvent ev) {
        setPfcpSeid(ev.message().seid());
    }

    private void onGtpEcho(GtpEchoEvent ev) {
        RaCommandPort port = gtpRa;
        if (port != null && !ev.response()) {
            port.sendCommand(new GtpEchoCommand(ev.peer()));
        }
    }

    private void onPfcpAssociation(PfcpAssociationEvent ev) {
        RaCommandPort port = pfcpRa;
        if (port != null && !ev.associated()) {
            port.sendCommand(new PfcpAssociateCommand(ev.peer()));
        }
    }

    private void onPfcpHeartbeat(PfcpHeartbeatEvent ev) {
        RaCommandPort port = pfcpRa;
        if (port != null && !ev.response()) {
            port.sendCommand(new PfcpAssociateCommand(ev.peer()));
        }
    }

    private void createSession(Gtpv2MessageEvent ev) {
        Gtpv2Message msg = ev.message();
        Fteid sender = GtpcShapes.fteidAt(msg, 0);
        String imsiDigits = Gtpv2Ies.imsiDigits(msg);
        String apnName = Gtpv2Ies.apn(msg);
        if (sender == null || imsiDigits == null || apnName == null) {
            send(error(msg, GtpcShapes.CAUSE_MANDATORY_IE_MISSING), ev.peer());
            return;
        }
        Imsi imsi = new Imsi(imsiDigits);
        ApnConfig apn = apnOf(apnName);
        setImsi(imsi.digits());
        setApn(apnName);
        if (sgw != null) {
            // GAP-sgw-pgw blocker 3: the MME's PGW S5/S8 control-plane F-TEID (instance 1)
            // selects the PGW-C for the S5-C leg.
            Fteid pgwS5c = GtpcShapes.fteidAt(msg, 1);
            List<Gtpv2Ie> bearer = GtpcShapes.firstBearer(msg);
            SgwControl.S11Context s11 = new SgwControl.S11Context(ev.peer(), msg.sequence(), msg.recovery(),
                    sender, GtpcShapes.ebi(msg, 5), GtpcShapes.bearerIe(bearer, GtpcShapes.BEARER_QOS),
                    passThrough(msg));
            sgw.beginCreateSession(imsi, apn, s11, pgwS5c).ifPresent(this::answerCreate);
            return;
        }
        if (pgw != null) {
            s5cCreateSession(ev, imsi, apn, sender);
            return;
        }
        send(error(msg, 73), ev.peer());
    }

    /**
     * PGW-C server side of S5-C (TS 29.274 §7.2.1-2): the SGW-C's Sender F-TEID (S5-C, instance 0)
     * and its S5/S8-U F-TEID in the Bearer Context; the answer goes to the SGW-C's S5-C TEID.
     */
    private void s5cCreateSession(Gtpv2MessageEvent ev, Imsi imsi, ApnConfig apn, Fteid sgwS5c) {
        Gtpv2Message msg = ev.message();
        List<Gtpv2Ie> bearer = GtpcShapes.firstBearer(msg);
        Fteid sgwS5u = GtpcShapes.bearerFteid(bearer, GtpcShapes.IF_S5U_SGW);
        if (sgwS5u == null) {
            send(new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, sgwS5c.teid(), msg.sequence(),
                    msg.recovery(), List.of(Gtpv2Ies.cause(GtpcShapes.CAUSE_MANDATORY_IE_MISSING))), ev.peer());
            return;
        }
        PgwS5cProcedure proc = pgwProcedure;
        if (proc != null) {
            // Two-phase with Gx (CCR-I → CCA event): never waits here.
            proc.begin(msg, ev.peer(), imsi, apn, sgwS5c, sgwS5u);
            return;
        }
        CreateSessionResult r = pgw.createSession(imsi, apn, sgwS5u);
        pgw.registerS5c(r.pgwS5c().teid(), new PgwControl.S5cPeer(imsi, apn.apn(), sgwS5c));
        setPfcpSeid(r.pfcpSeid());
        send(PgwS5cProcedure.response(msg, sgwS5c, GtpcShapes.ebi(msg, 5), r), ev.peer());
    }

    private void s5cResponse(Gtpv2MessageEvent ev) {
        if (sgw != null) {
            sgw.onS5cCreateResponse(ev.message()).ifPresent(this::answerCreate);
        }
    }

    private void s5cDeleteResponse(Gtpv2MessageEvent ev) {
        if (sgw != null) {
            sgw.onS5cDeleteResponse(ev.message()).ifPresent(this::answerDelete);
        }
    }

    /** T3 × N3 on an S5-C request this SGW-C sent: answer the MME now. */
    private void onGtpTimeout(GtpProcedureTimeoutEvent ev) {
        if (sgw == null) {
            return;
        }
        sgw.onS5cTimeout(ev.sequence()).ifPresent(outcome -> {
            switch (outcome) {
                case SgwControl.CreateOutcome c -> answerCreate(c);
                case SgwControl.DeleteOutcome d -> answerDelete(d);
                default -> {}
            }
        });
    }

    /** S11 Create Session Response (TS 29.274 Table 7.2.2-1), header TEID = MME S11 TEID. */
    private void answerCreate(SgwControl.CreateOutcome o) {
        SgwControl.S11Context s11 = o.s11();
        int mmeTeid = s11.mmeS11() == null ? 0 : s11.mmeS11().teid();
        if (!o.accepted()) {
            send(new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, mmeTeid, s11.sequence(),
                    s11.recovery(), List.of(Gtpv2Ies.cause(o.cause()))), s11.mme());
            return;
        }
        SgwCreateSessionResult r = o.result();
        setS11cTeid(r.sgwS11c().teid());
        setPfcpSeid(r.pgw().pfcpSeid());
        setGeneration(1L);
        List<Gtpv2Ie> ies = new java.util.ArrayList<>();
        ies.add(Gtpv2Ies.causeAccepted());
        ies.add(GtpcShapes.fteid(0, r.sgwS11c()));
        ies.add(GtpcShapes.fteid(1, r.pgw().pgwS5c()));
        if (r.ue().ipv4() != null) {
            ies.add(new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(r.ue().ipv4())));
        }
        ies.add(GtpcShapes.bearerContext(s11.ebi(), GtpcShapes.CAUSE_ACCEPTED, List.of(
                GtpcShapes.fteid(0, r.sgwS1u()),
                GtpcShapes.fteid(2, r.pgw().pgwS5u()))));
        // W3-b: relay PGW PCO (P-CSCF/DNS) verbatim to the MME.
        if (!r.pgw().pco().isEmpty()) {
            ies.add(Gtpv2Ies.pco(r.pgw().pco()));
        }
        send(new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, mmeTeid, s11.sequence(),
                s11.recovery(), ies), s11.mme());
    }

    private void answerDelete(SgwControl.DeleteOutcome o) {
        SgwControl.S11Context s11 = o.s11();
        Fteid mme = o.mmeS11() != null ? o.mmeS11() : s11.mmeS11();
        send(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_RESPONSE, mme == null ? 0 : mme.teid(),
                s11.sequence(), s11.recovery(), List.of(Gtpv2Ies.cause(o.cause()))), s11.mme());
    }

    private void modifyBearer(Gtpv2MessageEvent ev) {
        Gtpv2Message msg = ev.message();
        if (sgw == null) {
            send(error(msg, GtpcShapes.CAUSE_CONTEXT_NOT_FOUND), ev.peer());
            return;
        }
        Fteid mme = sgw.mmeS11(msg.teid());
        Fteid newEnb = GtpcShapes.bearerFteid(GtpcShapes.firstBearer(msg), GtpcShapes.IF_S1U_ENB);
        if (newEnb == null) {
            send(new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_RESPONSE, mme == null ? 0 : mme.teid(),
                    msg.sequence(), msg.recovery(), List.of(Gtpv2Ies.cause(GtpcShapes.CAUSE_MANDATORY_IE_MISSING))),
                    ev.peer());
            return;
        }
        Fteid s1u;
        try {
            s1u = sgw.modifyAccessByTeid(msg.teid(), newEnb);
        } catch (java.util.NoSuchElementException unknown) {
            send(new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_RESPONSE, mme == null ? 0 : mme.teid(),
                    msg.sequence(), msg.recovery(), List.of(Gtpv2Ies.cause(GtpcShapes.CAUSE_CONTEXT_NOT_FOUND))),
                    ev.peer());
            return;
        }
        setS11cTeid(msg.teid());
        // Table 7.2.8-1: Bearer Context modified {EBI, Cause, S1-U SGW F-TEID}.
        send(new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_RESPONSE, mme == null ? 0 : mme.teid(),
                msg.sequence(), msg.recovery(),
                List.of(Gtpv2Ies.causeAccepted(),
                        GtpcShapes.bearerContext(GtpcShapes.ebi(msg, 5), GtpcShapes.CAUSE_ACCEPTED,
                                List.of(GtpcShapes.fteid(0, s1u))))),
                ev.peer());
    }

    private void deleteSession(Gtpv2MessageEvent ev) {
        Gtpv2Message msg = ev.message();
        if (sgw != null) {
            SgwControl.S11Context s11 = new SgwControl.S11Context(ev.peer(), msg.sequence(), msg.recovery(),
                    sgw.mmeS11(msg.teid()), GtpcShapes.ebi(msg, 5), null, List.of());
            sgw.beginDeleteSession(msg.teid(), s11).ifPresent(this::answerDelete);
            return;
        }
        if (pgw != null) {
            // S5-C Delete Session on the PGW-C: correlated on our S5-C TEID; CCR-T after (§4.5.7).
            PgwControl.S5cPeer known = pgw.s5cPeer(msg.teid());
            long chargingId = known == null ? 0L : pgw.chargingId(known.imsi(), known.apn());
            PgwControl.S5cPeer peer = pgw.deleteByS5cTeid(msg.teid());
            PgwS5cProcedure proc = pgwProcedure;
            if (peer != null && proc != null) {
                proc.terminate(peer.imsi(), peer.apn(), chargingId);
            }
            send(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_RESPONSE,
                    peer == null ? 0 : peer.sgwS5c().teid(), msg.sequence(), msg.recovery(),
                    List.of(Gtpv2Ies.cause(peer == null
                            ? GtpcShapes.CAUSE_CONTEXT_NOT_FOUND : GtpcShapes.CAUSE_ACCEPTED))), ev.peer());
            return;
        }
        send(error(msg, GtpcShapes.CAUSE_CONTEXT_NOT_FOUND), ev.peer());
    }

    /** IEs the SGW-C carries from S11 onto S5-C (Table 7.2.1-1). */
    private static List<Gtpv2Ie> passThrough(Gtpv2Message msg) {
        List<Gtpv2Ie> out = new java.util.ArrayList<>();
        for (Gtpv2Ie ie : msg.ies()) {
            if (GtpcShapes.S5_PASS_THROUGH.contains(ie.type())) {
                out.add(ie);
            }
        }
        return out;
    }

    /**
     * Release Access Bearers: drop S1-U forwarding, keep S5. Always answer 171.
     * Cause 16 if the S11 TEID is known; 64 (Context not found) otherwise.
     */
    private void releaseAccessBearers(Gtpv2MessageEvent ev) {
        boolean released = sgw != null && sgw.releaseAccessByTeid(ev.message().teid());
        int cause = released ? Gtpv2Ies.CAUSE_REQUEST_ACCEPTED : Gtpv2Ies.CAUSE_CONTEXT_NOT_FOUND;
        // §5.5.2: the response carries the MME's S11 TEID, not ours (0 when the session is unknown).
        Fteid mme = sgw == null ? null : sgw.mmeS11(ev.message().teid());
        send(new Gtpv2Message(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE,
                mme == null ? 0 : mme.teid(), ev.message().sequence(), ev.message().recovery(),
                List.of(Gtpv2Ies.cause(cause))), ev.peer());
    }

    /** Inbound DDN (176) is odd on an SGW; still ack 177 so the peer is not left hanging. */
    private void downlinkDataNotification(Gtpv2MessageEvent ev) {
        send(new Gtpv2Message(Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE,
                ev.message().teid(), ev.message().sequence(), ev.message().recovery(),
                List.of(Gtpv2Ies.causeAccepted())), ev.peer());
    }

    private ApnConfig apnOf(String name) {
        if (profiles != null) {
            return EpcProfiles.findApn(profiles, name)
                    .map(GtpGatewaySbb::toApnConfig)
                    .orElseGet(() -> et.epc.api.PcoDefaults.named(name));
        }
        return et.epc.api.PcoDefaults.named(name);
    }

    private static ApnConfig toApnConfig(ApnProfile row) {
        return et.epc.api.PcoDefaults.named(row.getApn());
    }

    /** S5-C Create Session Response (Table 7.2.2-1, S5/S8), header TEID = SGW S5-C TEID. */
    private static Gtpv2Message error(Gtpv2Message req, int cause) {
        Gtpv2MessageType rsp = switch (req.type()) {
            case CREATE_SESSION_REQUEST -> Gtpv2MessageType.CREATE_SESSION_RESPONSE;
            case MODIFY_BEARER_REQUEST -> Gtpv2MessageType.MODIFY_BEARER_RESPONSE;
            case DELETE_SESSION_REQUEST -> Gtpv2MessageType.DELETE_SESSION_RESPONSE;
            case RELEASE_ACCESS_BEARERS_REQUEST -> Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE;
            case DOWNLINK_DATA_NOTIFICATION -> Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE;
            default -> Gtpv2MessageType.CREATE_SESSION_RESPONSE;
        };
        return new Gtpv2Message(rsp, req.teid(), req.sequence(), req.recovery(),
                List.of(Gtpv2Ies.cause(cause)));
    }

    private void send(Gtpv2Message msg, InetSocketAddress peer) {
        RaCommandPort port = gtpRa;
        if (port == null) {
            LOG.warn("GtpGatewaySbb: gtpv2c-ra not injected");
            return;
        }
        port.sendCommand(new SendGtpv2Message(msg, peer));
    }

    public static final class $Concrete extends GtpGatewaySbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(SgwControl sgw, PgwControl pgw, ProfileFacility profiles) {
            super(sgw, pgw, profiles);
        }

        @Override public String getImsi() { return str("imsi"); }
        @Override public void setImsi(String v) { put("imsi", v); write("setImsi", String.class, v); }
        @Override public String getApn() { return str("apn"); }
        @Override public void setApn(String v) { put("apn", v); write("setApn", String.class, v); }
        @Override public Integer getS11cTeid() { return i("s11cTeid"); }
        @Override public void setS11cTeid(Integer v) { put("s11cTeid", v); write("setS11cTeid", Integer.class, v); }
        @Override public Long getPfcpSeid() { return lng("pfcpSeid"); }
        @Override public void setPfcpSeid(Long v) { put("pfcpSeid", v); write("setPfcpSeid", Long.class, v); }
        @Override public Long getGeneration() { return lng("generation"); }
        @Override public void setGeneration(Long v) { put("generation", v); write("setGeneration", Long.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
        }

        private Integer i(String key) {
            Object v = local.get(key);
            return v instanceof Integer n ? n : null;
        }

        private Long lng(String key) {
            Object v = local.get(key);
            return v instanceof Long l ? l : null;
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
            } catch (IllegalStateException ignored) {
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return GtpGatewaySbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
