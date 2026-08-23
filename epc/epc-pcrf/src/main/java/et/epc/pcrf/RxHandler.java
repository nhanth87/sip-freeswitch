package et.epc.pcrf;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.QosProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rx (TS 29.214) AF-session binding. Binds an AF/PCSCF media session (media
 * IP + service flow) to a {@link PccRule} and maps it to Gx
 * Charging-Rule-Install/Remove ({@link GxHandler} flat encoders).
 *
 * <p>Pilot heuristic only: audio/RTP/voice flows are QCI 1, everything else
 * (SIP signalling) is QCI 5. End-to-end VoLTE QCI1 authorization needs the
 * Elisa Rx path — explicitly out of scope here.</p>
 */
public final class RxHandler {

    public static final long APP_ID = 16_777_236L;
    public static final int AA_CMD = 265;   // AAR/AAA
    public static final int RA_CMD = 258;   // RAR/RAA
    public static final int AS_CMD = 274;   // ASR/ASA
    public static final int ST_CMD = 275;   // STR/STA

    public static final int RESULT_CODE = 268;
    public static final int FRAMED_IP_ADDRESS = 8;
    public static final int FLOW_DESCRIPTION = 507;
    public static final int FLOW_NUMBER = 509;
    public static final int MEDIA_TYPE = 520;
    public static final int AF_APPLICATION_IDENTIFIER = 504;
    public static final int ABORT_CAUSE = 500;
    public static final int RE_AUTH_REQUEST_TYPE = 285;

    public static final String RE_AUTH_REQUEST_TYPE_AUTHORIZE_ONLY = "0";

    private static final long IP_CAN_TYPE_3GPP_EPS = 3L;
    private static final long RAT_TYPE_EUTRAN = 1004L;

    public static final String STATE_BOUND = "BOUND";
    public static final String STATE_TERMINATED = "TERMINATED";

    private final PcrfService pcrf;
    private final ConcurrentHashMap<String, AfSession> afSessions = new ConcurrentHashMap<>();

    public RxHandler(PcrfService pcrf) {
        this.pcrf = Objects.requireNonNull(pcrf);
    }

    /** AF session binding by media IP + service flow. */
    public record AfSession(String afSessionId, String mediaIpv4, String serviceFlow,
                            String ruleName, String state) {}

    public Map<Integer, String> handleAar(Map<Integer, String> avps, String afSessionId) {
        Map<Integer, String> a = avps == null ? Map.of() : avps;
        String mediaIpv4 = a.getOrDefault(FRAMED_IP_ADDRESS, "");
        String flow = a.getOrDefault(MEDIA_TYPE,
                a.getOrDefault(FLOW_DESCRIPTION, ""));
        installRules(afSessionId, mediaIpv4, flow);
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(RESULT_CODE, GxHandler.RESULT_SUCCESS);
        out.put(AF_APPLICATION_IDENTIFIER, a.getOrDefault(AF_APPLICATION_IDENTIFIER, ""));
        return Map.copyOf(out);
    }

    public Map<Integer, String> installRules(String afSessionId, String mediaIpv4, String serviceFlow) {
        Objects.requireNonNull(afSessionId);
        int qci = qciFor(serviceFlow);
        PccRule rule = qci == 1 ? PccRule.imsVoiceQci1() : PccRule.imsSignalQci5();
        afSessions.put(afSessionId, new AfSession(afSessionId, mediaIpv4, serviceFlow, rule.name(), STATE_BOUND));
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(GxHandler.CHARGING_RULE_INSTALL, GxHandler.encodeRule(rule));
        return Map.copyOf(out);
    }

    public Map<Integer, String> removeRule(String afSessionId) {
        AfSession s = afSessions.get(afSessionId);
        if (s == null) {
            return Map.of();
        }
        afSessions.replace(afSessionId, new AfSession(s.afSessionId(), s.mediaIpv4(),
                s.serviceFlow(), s.ruleName(), STATE_TERMINATED));
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(GxHandler.CHARGING_RULE_REMOVE, s.ruleName());
        return Map.copyOf(out);
    }

    /** RAR → RAA: acknowledges the PCRF-initiated re-auth request for the AF session. */
    public Map<Integer, String> handleRar(Map<Integer, String> avps, String afSessionId) {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(RESULT_CODE, GxHandler.RESULT_SUCCESS);
        return Map.copyOf(out);
    }

    /** STR → STA: terminates the AF session and returns the Gx remove AVP set. */
    public Map<Integer, String> handleStr(Map<Integer, String> avps, String afSessionId) {
        removeRule(afSessionId);
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(RESULT_CODE, GxHandler.RESULT_SUCCESS);
        return Map.copyOf(out);
    }

    public AfSession session(String afSessionId) {
        return afSessions.get(afSessionId);
    }

    /**
     * Light structured AAA body (independent of CCA/RAR builders). Open5GS Rx
     * expects IP-Can-Type=RAT 3GPP-EPS and RAT-Type=EUTRAN on the answer.
     */
    public List<DiameterAvp> aaaStructured(AfSession session) {
        Objects.requireNonNull(session);
        return List.of(
                DiameterAvp.v3gpp(GxHandler.IP_CAN_TYPE, IP_CAN_TYPE_3GPP_EPS),
                DiameterAvp.v3gpp(GxHandler.RAT_TYPE, RAT_TYPE_EUTRAN),
                DiameterAvp.enum32(GxHandler.AUTH_SESSION_STATE, 1L));
    }

    /** QCI classification heuristic: audio/voice/rtp → QCI 1, else QCI 5. */
    public static int qciFor(String serviceFlow) {
        String s = serviceFlow == null ? "" : serviceFlow.toLowerCase(Locale.ROOT);
        return (s.contains("audio") || s.contains("voice") || s.contains("rtp")) ? 1 : 5;
    }

    /** QCI profile correlate reused across Rx handlers (voLTE claim stays off). */
    public static QosProfile qosForQci(int qci) {
        if (qci == 1) {
            return new QosProfile(1, 1, true, false,
                    512_000_000L, 512_000_000L, 512_000_000L, 512_000_000L);
        }
        long g = 1_000_000_000L;
        return new QosProfile(5, 1, true, false, g, g, g, g);
    }

    /**
     * PCRF→AF server-initiated Re-Auth-Request (RAR) request AVPs. Returned as
     * a request to be sent via {@code diameter-ra} (SendDiameterRequest); the
     * inbound RAA is handled on the lead's DiameterGatewaySbb answer rail.
     */
    public Map<Integer, String> buildRar(String afSessionId) {
        Objects.requireNonNull(afSessionId);
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(RE_AUTH_REQUEST_TYPE, RE_AUTH_REQUEST_TYPE_AUTHORIZE_ONLY);
        return Map.copyOf(out);
    }

    /** PCRF→AF server-initiated Abort-Session-Request (ASR) request AVPs. */
    public Map<Integer, String> buildAsr(String afSessionId, String abortCause) {
        Objects.requireNonNull(afSessionId);
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(ABORT_CAUSE, abortCause == null ? "0" : abortCause);
        return Map.copyOf(out);
    }
}