package et.epc.pcef.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.api.Imsi;
import et.epc.pcef.Cdr;
import et.epc.pcef.ChargingId;
import et.epc.pcef.GyHandler;
import et.epc.pcef.PcefService;
import et.epc.pcef.PolicyDecision;
import et.epc.pcef.UsageRecord;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PCEF application SBB owning the policy-and-charging decision flow
 * ({@link PcefService}) for {@code PcefApp}. The vendor {@code diameter-ra}
 * supplies the Gx socket/codec; CMP keeps the live session/charging correlate.
 *
 * <p><b>Standalone model.</b> PCEF normally runs inside PGW-C (see {@code epc-pgwc}
 * {@code PgwSbb}/{@code PgwControl}). In standalone mode this SBB runs the
 * <em>decision core</em> only; Gx transport is wired by the host via the
 * {@code PcrfPort}-style delegation seam ({@link #handle(DiameterRequestEvent)})
 * bound to {@code DiameterRequestEvent}. In Gx the PCEF is the Diameter client
 * (CCR initiator); the only {@code diameter-ra} request a PCRF sends back is the
 * server-initiated Re-Auth-Request (RAR), acknowledged here with a successful RAA.</p>
 */
@SbbAnnotation(name = "PcefSbb", vendor = "et.epc", version = "1.0")
public abstract class PcefSbb extends CmpBackedSbb implements SleeEventHandler {

    /** Gx application-id (TS 29.212). */
    public static final long GX_APP_ID = 16_777_238L;
    /** PCRF-to-PCEF Re-Auth-Request/Answer command code (RAR/RAA). */
    public static final int RAR_CMD = 258;
    /** Result-Code AVP id. */
    public static final int RESULT_CODE = 268;
    /** DIAMETER_SUCCESS result code. */
    public static final String RESULT_SUCCESS = "2001";
    /** DIAMETER_APPLICATION_UNSUPPORTED result code. */
    public static final long APP_UNSUPPORTED = 3005L;

    /** Gx Charging-Rule RAR AVP codes (TS 29.212). */
    public static final int CHARGING_RULE_INSTALL = 1001;
    public static final int CHARGING_RULE_REMOVE = 1002;
    public static final int CHARGING_RULE_DEFINITION = 1003;
    public static final int CHARGING_RULE_NAME = 1005;

    private static final Logger LOG = LogManager.getLogger(PcefSbb.class);

    private final PcefService pcef;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public PcefSbb() {
        this(null);
    }

    public PcefSbb(PcefService pcef) {
        this.pcef = pcef != null ? pcef : new PcefService();
    }

    @CmpField("sessionId")
    public abstract String getSessionId();

    @CmpField("sessionId")
    public abstract void setSessionId(String sessionId);

    @CmpField("chargingId")
    public abstract Long getChargingId();

    @CmpField("chargingId")
    public abstract void setChargingId(Long chargingId);

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof DiameterAnswerEvent ans) {
            handleCca(ans);
            return;
        }
        if (!(event instanceof DiameterRequestEvent req)) {
            return;
        }
        SendDiameterAnswer answer = handle(req);
        RaCommandPort port = diameterRa;
        if (port != null && answer != null) {
            port.sendCommand(answer);
        }
    }

    /**
     * Gx inbound rail for the standalone PCEF. Only a Gx request is routed here;
     * the RAR is acknowledged with a re-auth-only successful RAA. Non-RAR Gx
     * requests (PCEF is the Gx client, so it should not receive CCR/CCA) are
     * answered with an application-unsupported error, mirroring the PcrfSbb rail.
     * Returns {@code null} for non-Gx events so the caller can skip sending.
     */
    public SendDiameterAnswer handle(DiameterRequestEvent req) {
        if (req == null || req.applicationId() != GX_APP_ID) {
            return null;
        }
        setSessionId(req.sessionId());
        if (req.commandCode() == RAR_CMD) {
            consumeRarRules(req, getChargingId());
            return SendDiameterAnswer.ok(req.sessionId(), GX_APP_ID, RAR_CMD,
                    req.hopByHopId(), req.endToEndId(), Map.of(RESULT_CODE, RESULT_SUCCESS));
        }
        return SendDiameterAnswer.error(req.sessionId(), GX_APP_ID, req.commandCode(),
                req.hopByHopId(), req.endToEndId(), APP_UNSUPPORTED);
    }

    /**
     * Consume a server-initiated Gx RAR's structured Charging-Rule-Install(1001)/
     * Charging-Rule-Remove(1002) AVPs onto the bound charging session. Install
     * carries Charging-Rule-Definition(1003)→Charging-Rule-Name(1005); remove
     * carries the rule name directly under Charging-Rule-Remove(1002).
     */
    private void consumeRarRules(DiameterRequestEvent req, Long chargingId) {
        List<DiameterAvp> avps = req.avpsStructured();
        if (avps == null || avps.isEmpty() || chargingId == null) {
            return;
        }
        List<String> installed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (DiameterAvp top : avps) {
            if (top == null) {
                continue;
            }
            if (top.code() == CHARGING_RULE_INSTALL) {
                for (DiameterAvp crd : children(top)) {
                    if (crd.code() == CHARGING_RULE_DEFINITION) {
                        String name = ruleNameOf(children(crd));
                        if (name != null) {
                            installed.add(name);
                        }
                    }
                }
            } else if (top.code() == CHARGING_RULE_REMOVE) {
                String name = ruleNameOf(children(top));
                if (name != null) {
                    removed.add(name);
                }
            }
        }
        if (!installed.isEmpty()) {
            pcef.installRules(chargingId, installed);
        }
        if (!removed.isEmpty()) {
            pcef.removeRules(chargingId, removed);
        }
    }

    private static List<DiameterAvp> children(DiameterAvp avp) {
        return avp.children() == null ? List.of() : avp.children();
    }

    private static String ruleNameOf(List<DiameterAvp> avps) {
        for (DiameterAvp a : avps) {
            if (a != null && a.code() == CHARGING_RULE_NAME) {
                return a.asUtf8();
            }
        }
        return null;
    }

    /** Opens a charging/policy IP-CAN session; returns the new charging id. */
    public ChargingId openSession(Imsi imsi, String apn, PcefService.Product product) {
        ChargingId id = pcef.open(imsi, apn, product);
        setChargingId(id.value());
        return id;
    }

    /** Policy decision for an APN (static fallback). */
    public PolicyDecision authorize(String apn) {
        return pcef.authorize(apn);
    }

    /** Policy decision for an IP-CAN session; consults the Gx PcrfClient when live. */
    public PolicyDecision authorize(Imsi imsi, String apn) {
        return pcef.authorize(imsi, apn);
    }

    public void reportUsage(UsageRecord usage) {
        pcef.reportUsage(usage);
    }

    public Cdr close(ChargingId id) {
        return pcef.close(id);
    }

    /**
     * Build a Gy CCR-InitiAl (Credit-Control-Request, 272) with the grouped
     * Multiple-Services-Credit-Control request body. The PCEF is the Gy client;
     * the returned {@link SendDiameterRequest} is emitted on {@code diameter-ra}.
     */
    public SendDiameterRequest buildCcrInitial(ChargingId id, long requestedOctets) {
        PcefService.SessionContext ctx = pcef.sessionContext(id);
        Map<Integer, String> flat = Map.of(
                GyHandler.CC_REQUEST_TYPE, GyHandler.REQ_TYPE_INITIAL,
                GyHandler.CC_REQUEST_NUMBER, "0");
        return new SendDiameterRequest(
                "gy-" + id.value(), GyHandler.APP_ID, GyHandler.CCR_CMD,
                null, null, flat,
                GyHandler.ccrInitialStructured(ctx.imsi(), requestedOctets));
    }

    /** Fire the Gy CCR-i outbound to the OCS when {@code diameter-ra} is bound. */
    public void fireCcrInitial(ChargingId id, long requestedOctets) {
        SendDiameterRequest req = buildCcrInitial(id, requestedOctets);
        RaCommandPort port = diameterRa;
        if (port != null) {
            port.sendCommand(req);
        } else {
            LOG.warn("PcefSbb Gy CCR-i dropped: diameter-ra not bound");
        }
    }

    /**
     * Consume an inbound Gy CCA (the OCS answer to our CCR) and apply the granted
     * quota onto the charging session. Non-Gy answers are ignored.
     */
    public GyHandler.Gcca handleCca(DiameterAnswerEvent answer) {
        if (answer == null || answer.applicationId() != GyHandler.APP_ID
                || answer.commandCode() != GyHandler.CCR_CMD) {
            return null;
        }
        GyHandler.Gcca gcca = GyHandler.Gcca.from(answer);
        if (gcca.grantedOctets() != null && gcca.grantedOctets() >= 0) {
            pcef.applyGrant(resolveChargingId(answer.sessionId()), gcca.grantedOctets());
        }
        return gcca;
    }

    /**
     * Recover the charging-id correlate from a Gy session-id. {@code gy-<id>} is
     * the canonical form produced by {@link #buildCcrInitial}; a non-numeric or
     * unknown session-id resolves to {@link ChargingId#NONE} so the grant is
     * dropped rather than attributed to the wrong session.
     */
    private ChargingId resolveChargingId(String sessionId) {
        if (sessionId == null || !sessionId.startsWith("gy-")) {
            return ChargingId.NONE;
        }
        try {
            return new ChargingId(Long.parseLong(sessionId.substring(3)));
        } catch (NumberFormatException e) {
            return ChargingId.NONE;
        }
    }

    public PcefService pcef() {
        return pcef;
    }

    public static final class $Concrete extends PcefSbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(PcefService pcef) {
            super(pcef);
        }

        @Override public String getSessionId() { return str("sessionId"); }
        @Override public void setSessionId(String v) { put("sessionId", v); write("setSessionId", String.class, v); }
        @Override public Long getChargingId() { return lng("chargingId"); }
        @Override public void setChargingId(Long v) { put("chargingId", v); write("setChargingId", Long.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
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
                // local map when container store unbound
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return PcefSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}