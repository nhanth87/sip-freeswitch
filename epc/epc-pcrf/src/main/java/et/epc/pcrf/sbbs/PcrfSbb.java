package et.epc.pcrf.sbbs;

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
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.pcrf.GxHandler;
import et.epc.pcrf.PccDecision;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.RxHandler;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PCRF Gx/Rx application SBB. Owns Gx (CCR→CCA) and Rx (AAR→AAA, STR→STA,
 * RAR→RAA) control plane; transport, DER/DEA peer liveness and the Diameter
 * socket stay on {@code diameter-ra}.
 *
 * <p>Exposes {@link #handle(DiameterRequestEvent)} so {@code epc-host}'s
 * DiameterGatewaySbb can delegate Gx/Rx here through {@code PcrfPort} without
 * opening a second Diameter socket.</p>
 */
@SbbAnnotation(name = "PcrfSbb", vendor = "et.epc", version = "1.0")
public abstract class PcrfSbb extends CmpBackedSbb implements SleeEventHandler {

    public static final long GX = 16_777_238L;
    public static final long RX = 16_777_236L;

    /** Gx PCRF→PCEF Re-Auth-Request/Answer command code (TS 29.212). */
    public static final int GX_RAR_CMD = 258;
    /** Rx PCRF→AF Re-Auth-Request (258) and Abort-Session-Request (274) (TS 29.214). */
    public static final int RX_RAR_CMD = 258;
    public static final int ASR_CMD = 274;

    private static final Logger LOG = LogManager.getLogger(PcrfSbb.class);

    private final PcrfService pcrf;
    private final GxHandler gx;
    private final RxHandler rx;

    /** Config {@code epc.pcrf.server-init.enabled} (default true): gates RAR/ASR push. */
    private volatile boolean serverInitEnabled;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public PcrfSbb() {
        this(null);
    }

    public PcrfSbb(PcrfService pcrf) {
        this.pcrf = pcrf != null ? pcrf : PcrfService.withHeapPolicy();
        this.gx = new GxHandler(this.pcrf);
        this.rx = new RxHandler(this.pcrf);
        this.serverInitEnabled = serverInitDefault();
    }

    @CmpField("sessionId")
    public abstract String getSessionId();

    @CmpField("sessionId")
    public abstract void setSessionId(String sessionId);

    @CmpField("applicationId")
    public abstract Long getApplicationId();

    @CmpField("applicationId")
    public abstract void setApplicationId(Long applicationId);

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
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
     * Demux by Diameter {@code applicationId} + {@code commandCode} and return
     * the answer to send. The {@code epc-host} gateway binds this method as a
     * {@code PcrfPort} for Gx/Rx delegation.
     */
    public SendDiameterAnswer handle(DiameterRequestEvent req) {
        if (req == null) {
            return null;
        }
        setSessionId(req.sessionId());
        setApplicationId(req.applicationId());
        return switch ((int) req.applicationId()) {
            case (int) GX -> gx(req);
            case (int) RX -> rx(req);
            default -> SendDiameterAnswer.error(
                    req.sessionId(), req.applicationId(), req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3007L);
        };
    }

    private SendDiameterAnswer gx(DiameterRequestEvent req) {
        if (req.commandCode() != GxHandler.CCR_CMD) {
            return unsupported(req, GX);
        }
        Map<Integer, String> ccr = enrichedAvps(req);
        Map<Integer, String> cca = gx.handleCcr(ccr, req.sessionId());
        List<DiameterAvp> structured = List.of();
        if (GxHandler.RESULT_SUCCESS.equals(cca.get(GxHandler.RESULT_CODE))) {
            GxHandler.GxCcr parsed = GxHandler.GxCcr.parse(ccr, req.sessionId());
            PccDecision decision = pcrf.decide(parsed.imsi(), parsed.apn());
            structured = gx.ccaStructured(parsed, decision);
        }
        return answer(req, GX, cca, structured);
    }

    private SendDiameterAnswer rx(DiameterRequestEvent req) {
        Map<Integer, String> out = switch (req.commandCode()) {
            case RxHandler.AA_CMD -> rx.handleAar(safeAvps(req), req.sessionId());
            case RxHandler.ST_CMD -> rx.handleStr(safeAvps(req), req.sessionId());
            case RxHandler.RA_CMD -> rx.handleRar(safeAvps(req), req.sessionId());
            default -> null;
        };
        if (out == null) {
            return unsupported(req, RX);
        }
        List<DiameterAvp> structured = List.of();
        if (req.commandCode() == RxHandler.AA_CMD) {
            RxHandler.AfSession session = rx.session(req.sessionId());
            if (session != null) {
                structured = rx.aaaStructured(session);
            }
        }
        return answer(req, RX, out, structured);
    }

    private SendDiameterAnswer unsupported(DiameterRequestEvent req, long applicationId) {
        return SendDiameterAnswer.error(req.sessionId(), applicationId, req.commandCode(),
                req.hopByHopId(), req.endToEndId(), 3005L);
    }

    private static SendDiameterAnswer answer(DiameterRequestEvent req, long applicationId,
                                            Map<Integer, String> avps,
                                            List<DiameterAvp> structured) {
        Map<Integer, String> a = avps == null ? Map.of() : avps;
        long resultCode = SendDiameterAnswer.SUCCESS;
        String rc = a.get(GxHandler.RESULT_CODE);
        if (rc != null && !rc.isBlank()) {
            try {
                resultCode = Long.parseLong(rc.trim());
            } catch (NumberFormatException ignore) {
                // keep DIAMETER_SUCCESS
            }
        }
        return new SendDiameterAnswer(req.sessionId(), applicationId, req.commandCode(),
                resultCode, req.hopByHopId(), req.endToEndId(), a,
                structured == null ? List.of() : List.copyOf(structured));
    }

    private static Map<Integer, String> safeAvps(DiameterRequestEvent req) {
        return req.avps() == null ? Map.of() : req.avps();
    }

    /**
     * Gx CCR may carry Subscription-Id-Data (444) inside a grouped Subscription-Id,
     * so the typed AVP layer rather than the flat map. Fall back to the structured
     * list for IMSI/APN when the flat map lacks them.
     */
    private static Map<Integer, String> enrichedAvps(DiameterRequestEvent req) {
        Map<Integer, String> a = new LinkedHashMap<>(safeAvps(req));
        putIfAbsent(a, req, GxHandler.SUBSCRIPTION_ID_DATA);
        putIfAbsent(a, req, GxHandler.CALLED_STATION_ID);
        return a;
    }

    private static void putIfAbsent(Map<Integer, String> a, DiameterRequestEvent req, int code) {
        if (a.containsKey(code) && a.get(code) != null && !a.get(code).isBlank()) {
            return;
        }
        String v = findAvp(req.avpsStructured(), code);
        if (v != null && !v.isBlank()) {
            a.put(code, v);
        }
    }

    /** Depth-first lookup across typed/grouped vendor AVPs. */
    private static String findAvp(List<DiameterAvp> avps, int code) {
        if (avps == null) {
            return null;
        }
        for (DiameterAvp avp : avps) {
            if (avp == null) {
                continue;
            }
            if (avp.code() == code) {
                String leaf = leafValue(avp);
                if (leaf != null) {
                    return leaf;
                }
            }
            String nested = findAvp(avp.children(), code);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static String leafValue(DiameterAvp avp) {
        Object v = avp.value();
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof byte[] b) {
            return new String(b, StandardCharsets.UTF_8);
        }
        return v.toString();
    }

    /** Server-initiated Gx RAR push action (install vs remove a PCC rule). */
    public enum GxRarAction { INSTALL, REMOVE }

    /**
     * Build a server-initiated Gx RAR to the PCEF carrying a structured
     * Charging-Rule-Install(1001)→Charging-Rule-Definition(1003) or
     * Charging-Rule-Remove(1002) AVP. {@code destinationHost}/{@code destinationRealm}
     * are left to the RA peer route (lab default); a caller with session context can
     * set them on the returned command before {@link RaCommandPort#sendCommand}.
     */
    public SendDiameterRequest buildGxRar(String sessionId, GxRarAction action, PccRule rule) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(rule);
        List<DiameterAvp> structured = switch (action) {
            case INSTALL -> List.of(GxHandler.chargingRuleInstallStructured(rule));
            case REMOVE -> List.of(GxHandler.chargingRuleRemoveStructured(rule.name()));
        };
        return new SendDiameterRequest(sessionId, GX, GX_RAR_CMD,
                null, null, Map.of(), structured);
    }

    /** Fire the Gx RAR push out on {@code diameter-ra} when server-init is enabled. */
    public void fireRar(String sessionId, GxRarAction action, PccRule rule) {
        sendServerInit(buildGxRar(sessionId, action, rule));
    }

    /** Fire a Gx RAR push for the CMP-bound session (captured from the last inbound event). */
    public void fireRar(GxRarAction action, PccRule rule) {
        fireRar(getSessionId(), action, rule);
    }

    /**
     * Policy-change push path (Phase C3): after the admin write to
     * {@code epc_pcc_rule} lands, invalidate the cached rule set for
     * {@code (imsi, apn)} (ISPN when wired), re-decide, look up the active Gx
     * Session-Ids from {@code epc_ipcan_session} and push a Gx RAR
     * (Charging-Rule-Install per fresh rule) to each PCEF session over
     * {@code diameter-ra}. {@code adminSessionId} is the admin-write
     * correlation token (log-only; may be {@code null}).
     *
     * <p>Honest no-op semantics: returns 0 and sends nothing when there are no
     * active sessions, when the fresh decision has no rules, when server-init
     * is disabled, or when the RA port is unbound.</p>
     *
     * @return the number of Gx sessions a RAR fan-out was emitted for
     */
    public int pushRarOnPolicyChange(String adminSessionId, String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        PccDecision decision = pcrf.policyChanged(imsi, apn);
        List<String> gxSessions = pcrf.activeGxSessionIds(imsi, apn);
        if (gxSessions.isEmpty()) {
            LOG.debug("PcrfSbb policy change admin={} imsi={} apn={}: no active Gx sessions",
                    adminSessionId, imsi, apn);
            return 0;
        }
        if (decision.rules().isEmpty()) {
            LOG.info("PcrfSbb policy change admin={} imsi={} apn={}: fresh decision has no"
                    + " rules — RAR fan-out skipped", adminSessionId, imsi, apn);
            return 0;
        }
        if (!serverInitEnabled || diameterRa == null) {
            LOG.warn("PcrfSbb policy change admin={} imsi={} apn={}: push disabled/unbound,"
                    + " dropped RAR for {} sessions", adminSessionId, imsi, apn, gxSessions.size());
            return 0;
        }
        for (String gxSessionId : gxSessions) {
            for (PccRule rule : decision.rules()) {
                fireRar(gxSessionId, GxRarAction.INSTALL, rule);
            }
        }
        LOG.info("PcrfSbb policy change admin={} imsi={} apn={}: RAR pushed to {} Gx sessions"
                + " ({} rules each)", adminSessionId, imsi, apn, gxSessions.size(),
                decision.rules().size());
        return gxSessions.size();
    }

    /** Build a server-initiated Rx RAR (258) to the AF for the given AF session. */
    public SendDiameterRequest buildRxRar(String afSessionId) {
        return new SendDiameterRequest(afSessionId, RX, RX_RAR_CMD,
                null, null, rx.buildRar(afSessionId), List.of());
    }

    /** Fire the Rx RAR push to the AF when server-init is enabled. */
    public void fireRxRar(String afSessionId) {
        sendServerInit(buildRxRar(afSessionId));
    }

    /** Build a server-initiated Rx Abort-Session-Request (ASR, 274) to the AF. */
    public SendDiameterRequest buildAsr(String afSessionId) {
        return new SendDiameterRequest(afSessionId, RX, ASR_CMD,
                null, null, rx.buildAsr(afSessionId, "1"), List.of());
    }

    /** Fire the Rx ASR push to the AF when server-init is enabled. */
    public void fireAsr(String afSessionId) {
        sendServerInit(buildAsr(afSessionId));
    }

    /**
     * Outbound Diameter command-port seam (the request sibling of the existing
     * answer send path). Emits {@link SendDiameterRequest} on {@code diameter-ra};
     * no-ops when server-initiated push is disabled or the RA is not bound.
     */
    private void sendServerInit(SendDiameterRequest req) {
        if (!serverInitEnabled) {
            LOG.debug("PcrfSbb server-initiated push disabled; dropping cmd={}", req.commandCode());
            return;
        }
        RaCommandPort port = diameterRa;
        if (port == null) {
            LOG.warn("PcrfSbb server-initiated push dropped: diameter-ra not bound");
            return;
        }
        port.sendCommand(req);
    }

    public boolean serverInitEnabled() {
        return serverInitEnabled;
    }

    public void setServerInitEnabled(boolean on) {
        this.serverInitEnabled = on;
    }

    /** Resolve {@code epc.pcrf.server-init.enabled} (system property then env), default true. */
    static boolean serverInitDefault() {
        String v = System.getProperty("epc.pcrf.server-init.enabled");
        if (v == null || v.isBlank()) {
            v = System.getenv("EPC_PCRF_SERVER_INIT_ENABLED");
        }
        return v == null || v.isBlank() || Boolean.parseBoolean(v);
    }

    public static final class $Concrete extends PcrfSbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(PcrfService pcrf) {
            super(pcrf);
        }

        @Override public String getSessionId() { return str("sessionId"); }
        @Override public void setSessionId(String v) { put("sessionId", v); write("setSessionId", String.class, v); }
        @Override public Long getApplicationId() { return lng("applicationId"); }
        @Override public void setApplicationId(Long v) { put("applicationId", v); write("setApplicationId", Long.class, v); }

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
                return PcrfSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}