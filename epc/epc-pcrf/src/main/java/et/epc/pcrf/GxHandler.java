package et.epc.pcrf;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.QosProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gx (TS 29.212) CCR/CCA handler over the vendor {@code ra-diameter} flat AVP
 * model: {@code Map<Integer,String>}. Transport never lives here. Command code
 * for both CCR and CCA is {@link #CCR_CMD} (272).
 *
 * <p><b>Flat AVP encoding contract</b> (documented so the lead can wire
 * {@code DiameterGatewaySbb}):</p>
 * <ul>
 *   <li>Discovery is Application-Id {@link #APP_ID} ({@code 16777238}); demux
 *       on command code 272.</li>
 *   <li>Request-side keys: IMSI = {@link #SUBSCRIPTION_ID_DATA} (444), APN =
 *       {@link #CALLED_STATION_ID} (30), UE IP = {@link #FRAMED_IP_ADDRESS}
 *       (8), charging id = {@link #CHARGING_ID} (2), request type =
 *       {@link #CC_REQUEST_TYPE} (416).</li>
 *   <li>Answer-side keys: {@link #RESULT_CODE} (268), {@link #CC_REQUEST_TYPE}
 *       (416), {@link #CC_REQUEST_NUMBER} (415),
 *       {@link #CHARGING_RULE_INSTALL} (1001), {@link #DEFAULT_EPS_BEARER_QOS}
 *       (1023), {@link #USAGE_MONITORING_INFORMATION} (1067).</li>
 *   <li>Grouped AVPs are strings because the vendor map is single-valued.
 *       Rules: {@code name=..;qci=..;arp=p,cap,vuln;mbr=ul,dl;gbr=ul,dl;
 *       ratingGroup=r|_;serviceId=s|_;flow=..;gating=true|false}. Multiple
 *       rules are pipe-joined ({@code |}).</li>
 * </ul>
 */
public final class GxHandler {

    public static final long APP_ID = 16_777_238L;
    public static final int CCR_CMD = 272;

    public static final int USER_NAME = 1;
    public static final int FRAMED_IP_ADDRESS = 8;
    public static final int CALLED_STATION_ID = 30;
    public static final int CHARGING_ID = 2;                 // 3GPP-Charging-Id (TS 32.299)
    public static final int RESULT_CODE = 268;
    public static final int AUTH_SESSION_STATE = 277;         // base Diameter enum, NO_STATE_MAINTAINED=1
    public static final int CC_REQUEST_TYPE = 416;
    public static final int CC_REQUEST_NUMBER = 415;
    public static final int SUBSCRIPTION_ID_DATA = 444;
    public static final int SUBSCRIPTION_ID_TYPE = 450;
    public static final int CHARGING_RULE_INSTALL = 1001;
    public static final int CHARGING_RULE_REMOVE = 1002;
    public static final int CHARGING_RULE_DEFINITION = 1003;
    public static final int CHARGING_RULE_NAME = 1005;
    public static final int DEFAULT_EPS_BEARER_QOS = 1023;
    public static final int USAGE_MONITORING_INFORMATION = 1067;
    public static final int FLOW_DESCRIPTION = 507;
    public static final int FLOW_STATUS = 511;
    public static final int RATING_GROUP = 432;
    public static final int SERVICE_IDENTIFIER = 439;
    public static final int ONLINE = 1009;
    public static final int OFFLINE = 1008;
    public static final int FLOW_INFORMATION = 1058;
    public static final int FLOW_DIRECTION = 1080;
    public static final int PRECEDENCE = 1017;
    public static final int QOS_INFORMATION = 1016;
    public static final int QOS_CLASS_IDENTIFIER = 1028;
    public static final int MAX_REQUESTED_BANDWIDTH_UL = 516;
    public static final int MAX_REQUESTED_BANDWIDTH_DL = 517;
    public static final int GUARANTEED_BITRATE_UL = 518;
    public static final int GUARANTEED_BITRATE_DL = 519;
    public static final int ALLOCATION_RETENTION_PRIORITY = 1034;
    public static final int PRIORITY_LEVEL = 1046;
    public static final int PRE_EMPTION_CAPABILITY = 1047;
    public static final int PRE_EMPTION_VULNERABILITY = 1048;
    public static final int APN_AGGREGATE_MAX_BITRATE_UL = 1041;
    public static final int APN_AGGREGATE_MAX_BITRATE_DL = 1042;
    public static final int IP_CAN_TYPE = 1027;
    public static final int RAT_TYPE = 1032;

    /** 3GPP vendor id used on Gx/Rx vendor-specific AVPs (TS 29.212/29.214). */
    public static final long VENDOR_3GPP = 10415L;

    /** Flow-Direction (1080) values as used by the Open5GS Gx dictionary. */
    public static final long FLOW_DIRECTION_UNSPECIFIED = 1L;
    public static final long FLOW_DIRECTION_DOWNLINK = 2L;
    public static final long FLOW_DIRECTION_UPLINK = 3L;
    public static final long FLOW_DIRECTION_BIDIRECTIONAL = 4L;

    private static final long ENUM_ENABLED = 1L;
    private static final long ENUM_DISABLED = 0L;
    private static final long DEFAULT_RULE_PRECEDENCE = 1L;

    public static final String RESULT_SUCCESS = "2001";
    public static final String RESULT_USER_UNKNOWN = "5001";
    public static final String REQ_TYPE_INITIAL = "1";
    public static final String REQ_TYPE_UPDATE = "2";
    public static final String REQ_TYPE_TERMINATION = "3";

    private final PcrfService pcrf;

    public GxHandler(PcrfService pcrf) {
        this.pcrf = Objects.requireNonNull(pcrf);
    }

    /** Parsed CCR request fields (request flag is conveyed by the event type). */
    public record GxCcr(String sessionId, String imsi, String apn, String framedIp,
                        long chargingId, String requestType, long ccRequestNumber) {
        public static GxCcr parse(Map<Integer, String> avps, String sessionId) {
            Map<Integer, String> a = avps == null ? Map.of() : avps;
            String imsi = a.getOrDefault(SUBSCRIPTION_ID_DATA,
                    a.getOrDefault(USER_NAME, ""));
            return new GxCcr(
                    sessionId,
                    imsi,
                    a.getOrDefault(CALLED_STATION_ID, ""),
                    a.getOrDefault(FRAMED_IP_ADDRESS, ""),
                    parseLong(a.get(CHARGING_ID), 0L),
                    a.getOrDefault(CC_REQUEST_TYPE, REQ_TYPE_INITIAL),
                    parseLong(a.get(CC_REQUEST_NUMBER), 1L));
        }
    }

    /** Full CCR→CCA. Decides via {@link PcrfService}, persists the snapshot, encodes CCA. */
    public Map<Integer, String> handleCcr(Map<Integer, String> avps, String sessionId) {
        GxCcr ccr = GxCcr.parse(avps, sessionId);
        if (ccr.imsi().isBlank()) {
            Map<Integer, String> err = new LinkedHashMap<>();
            err.put(RESULT_CODE, RESULT_USER_UNKNOWN);
            return Map.copyOf(err);
        }
        PccDecision decision = pcrf.decide(ccr.imsi(), ccr.apn());
        pcrf.persistDecision(sessionId, ccr.imsi(), ccr.apn(),
                ccr.framedIp(), ccr.chargingId(), decision);
        return cca(ccr, decision);
    }

    public Map<Integer, String> cca(GxCcr ccr, PccDecision decision) {
        Objects.requireNonNull(ccr);
        Objects.requireNonNull(decision);
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        out.put(RESULT_CODE, RESULT_SUCCESS);
        out.put(CC_REQUEST_TYPE, ccr.requestType());
        out.put(CC_REQUEST_NUMBER, Long.toString(ccr.ccRequestNumber()));
        if (!decision.rules().isEmpty()) {
            out.put(CHARGING_RULE_INSTALL, encodeRules(decision.rules()));
        }
        out.put(DEFAULT_EPS_BEARER_QOS, encodeDefaultBearerQos(decision.defaultBearerQos()));
        if (decision.usageMonitorThreshold() != null) {
            out.put(USAGE_MONITORING_INFORMATION,
                    encodeUsageMonitor(decision.usageMonitorThreshold()));
        }
        return Map.copyOf(out);
    }

    /**
     * Grouped CCA body for the RA structured-AVP layer. Kept additive: the flat
     * string encoders above stay the authority for tests, debug logs and the
     * persistence snapshot; this carries the real 3GPP groups a PCEF can decode.
     */
    public List<DiameterAvp> ccaStructured(GxCcr ccr, PccDecision decision) {
        Objects.requireNonNull(ccr);
        Objects.requireNonNull(decision);
        List<DiameterAvp> body = new ArrayList<>();
        if (!decision.rules().isEmpty()) {
            List<DiameterAvp> install = new ArrayList<>();
            for (PccRule rule : decision.rules()) {
                install.add(chargingRuleDefinition(rule, decision.defaultBearerQos()));
            }
            body.add(DiameterAvp.grouped3gpp(CHARGING_RULE_INSTALL, install));
        }
        body.add(defaultEpsBearerQosGroup(decision.defaultBearerQos()));
        body.add(DiameterAvp.enum32(AUTH_SESSION_STATE, 1L));
        return List.copyOf(body);
    }

    /**
     * Structured Charging-Rule-Install(1001) AVP for a server-initiated Gx RAR
     * push to the PCEF. Carries a self-contained Charging-Rule-Definition(1003)
     * built from the rule alone (no session default-bearer QoS dependency).
     */
    public static DiameterAvp chargingRuleInstallStructured(PccRule rule) {
        Objects.requireNonNull(rule);
        List<DiameterAvp> def = new ArrayList<>();
        def.add(DiameterAvp.utf8(CHARGING_RULE_NAME, VENDOR_3GPP, rule.name(), true));
        if (rule.flowDescription() != null && !rule.flowDescription().isBlank()) {
            def.add(DiameterAvp.grouped3gpp(FLOW_INFORMATION, List.of(
                    DiameterAvp.octets(FLOW_DESCRIPTION, VENDOR_3GPP,
                            rule.flowDescription().getBytes(StandardCharsets.UTF_8), true),
                    DiameterAvp.v3gpp(FLOW_DIRECTION, FLOW_DIRECTION_BIDIRECTIONAL),
                    DiameterAvp.v3gpp(FLOW_STATUS, rule.gatingOpen() ? ENUM_ENABLED : ENUM_DISABLED))));
        }
        if (rule.ratingGroup() != null) {
            def.add(DiameterAvp.u32(RATING_GROUP, rule.ratingGroup().longValue()));
        }
        Integer serviceId = parseServiceId(rule.serviceId());
        if (serviceId != null) {
            def.add(DiameterAvp.u32(SERVICE_IDENTIFIER, serviceId.longValue()));
        }
        def.add(DiameterAvp.enum32(ONLINE, ENUM_DISABLED));
        def.add(DiameterAvp.enum32(OFFLINE, ENUM_ENABLED));
        def.add(DiameterAvp.u32(PRECEDENCE, VENDOR_3GPP, DEFAULT_RULE_PRECEDENCE, true));
        def.add(DiameterAvp.grouped3gpp(QOS_INFORMATION, List.of(
                DiameterAvp.v3gpp(QOS_CLASS_IDENTIFIER, rule.qci()),
                DiameterAvp.u32(MAX_REQUESTED_BANDWIDTH_UL, VENDOR_3GPP, rule.mbrUlBps(), true),
                DiameterAvp.u32(MAX_REQUESTED_BANDWIDTH_DL, VENDOR_3GPP, rule.mbrDlBps(), true),
                DiameterAvp.u32(GUARANTEED_BITRATE_UL, VENDOR_3GPP, rule.gbrUlBps(), true),
                DiameterAvp.u32(GUARANTEED_BITRATE_DL, VENDOR_3GPP, rule.gbrDlBps(), true),
                arpGroup(rule.arpPriority(), rule.preemptionCapable(), rule.preemptionVulnerable()))));
        return DiameterAvp.grouped3gpp(CHARGING_RULE_INSTALL, List.of(
                DiameterAvp.grouped3gpp(CHARGING_RULE_DEFINITION, def)));
    }

    /** Structured Charging-Rule-Remove(1002) AVP for a server-initiated Gx RAR push. */
    public static DiameterAvp chargingRuleRemoveStructured(String ruleName) {
        return DiameterAvp.grouped3gpp(CHARGING_RULE_REMOVE, List.of(
                DiameterAvp.utf8(CHARGING_RULE_NAME, VENDOR_3GPP, ruleName, true)));
    }

    private static DiameterAvp chargingRuleDefinition(PccRule r, QosProfile sessionQos) {
        List<DiameterAvp> def = new ArrayList<>();
        def.add(DiameterAvp.utf8(CHARGING_RULE_NAME, VENDOR_3GPP, r.name(), true));
        if (r.flowDescription() != null && !r.flowDescription().isBlank()) {
            def.add(DiameterAvp.grouped3gpp(FLOW_INFORMATION, List.of(
                    DiameterAvp.octets(FLOW_DESCRIPTION, VENDOR_3GPP,
                            r.flowDescription().getBytes(StandardCharsets.UTF_8), true),
                    DiameterAvp.v3gpp(FLOW_DIRECTION, FLOW_DIRECTION_BIDIRECTIONAL),
                    DiameterAvp.v3gpp(FLOW_STATUS, r.gatingOpen() ? ENUM_ENABLED : ENUM_DISABLED))));
        }
        if (r.ratingGroup() != null) {
            def.add(DiameterAvp.u32(RATING_GROUP, r.ratingGroup().longValue()));
        }
        Integer serviceId = parseServiceId(r.serviceId());
        if (serviceId != null) {
            def.add(DiameterAvp.u32(SERVICE_IDENTIFIER, serviceId.longValue()));
        }
        def.add(DiameterAvp.enum32(ONLINE, ENUM_DISABLED));
        def.add(DiameterAvp.enum32(OFFLINE, ENUM_ENABLED));
        def.add(DiameterAvp.u32(PRECEDENCE, VENDOR_3GPP, DEFAULT_RULE_PRECEDENCE, true));
        def.add(qosInformation(r, sessionQos));
        return DiameterAvp.grouped3gpp(CHARGING_RULE_DEFINITION, def);
    }

    private static DiameterAvp qosInformation(PccRule r, QosProfile sessionQos) {
        return DiameterAvp.grouped3gpp(QOS_INFORMATION, List.of(
                DiameterAvp.v3gpp(QOS_CLASS_IDENTIFIER, r.qci()),
                DiameterAvp.u32(MAX_REQUESTED_BANDWIDTH_UL, VENDOR_3GPP, r.mbrUlBps(), true),
                DiameterAvp.u32(MAX_REQUESTED_BANDWIDTH_DL, VENDOR_3GPP, r.mbrDlBps(), true),
                DiameterAvp.u32(GUARANTEED_BITRATE_UL, VENDOR_3GPP, r.gbrUlBps(), true),
                DiameterAvp.u32(GUARANTEED_BITRATE_DL, VENDOR_3GPP, r.gbrDlBps(), true),
                arpGroup(r.arpPriority(), r.preemptionCapable(), r.preemptionVulnerable()),
                DiameterAvp.u32(APN_AGGREGATE_MAX_BITRATE_UL, VENDOR_3GPP,
                        sessionQos.apnAmbrUlBps(), true),
                DiameterAvp.u32(APN_AGGREGATE_MAX_BITRATE_DL, VENDOR_3GPP,
                        sessionQos.apnAmbrDlBps(), true)));
    }

    private static DiameterAvp defaultEpsBearerQosGroup(QosProfile q) {
        return DiameterAvp.grouped3gpp(DEFAULT_EPS_BEARER_QOS, List.of(
                DiameterAvp.v3gpp(QOS_CLASS_IDENTIFIER, q.qci()),
                arpGroup(q.arpPriority(), q.preemptionCapability(), q.preemptionVulnerability())));
    }

    private static DiameterAvp arpGroup(int priority, boolean capable, boolean vulnerable) {
        return DiameterAvp.grouped3gpp(ALLOCATION_RETENTION_PRIORITY, List.of(
                DiameterAvp.u32(PRIORITY_LEVEL, VENDOR_3GPP, priority, true),
                DiameterAvp.v3gpp(PRE_EMPTION_CAPABILITY, capable ? ENUM_ENABLED : ENUM_DISABLED),
                DiameterAvp.v3gpp(PRE_EMPTION_VULNERABILITY,
                        vulnerable ? ENUM_ENABLED : ENUM_DISABLED)));
    }

    private static Integer parseServiceId(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String encodeRules(List<PccRule> rules) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                b.append('|');
            }
            b.append(encodeRule(rules.get(i)));
        }
        return b.toString();
    }

    public static String encodeRule(PccRule r) {
        return "name=" + r.name()
                + ";qci=" + r.qci()
                + ";arp=" + r.arpPriority() + "," + bit(r.preemptionCapable()) + "," + bit(r.preemptionVulnerable())
                + ";mbr=" + r.mbrUlBps() + "," + r.mbrDlBps()
                + ";gbr=" + r.gbrUlBps() + "," + r.gbrDlBps()
                + ";ratingGroup=" + (r.ratingGroup() == null ? "-" : r.ratingGroup())
                + ";serviceId=" + (r.serviceId() == null ? "-" : r.serviceId())
                + ";flow=" + (r.flowDescription() == null ? "" : r.flowDescription())
                + ";gating=" + r.gatingOpen();
    }

    public static String encodeDefaultBearerQos(QosProfile q) {
        return "qci=" + q.qci()
                + ";arp=" + q.arpPriority() + "," + bit(q.preemptionCapability())
                + "," + bit(q.preemptionVulnerability())
                + ";apnAmbr=" + q.apnAmbrUlBps() + "," + q.apnAmbrDlBps()
                + ";ueAmbr=" + q.ueAmbrUlBps() + "," + q.ueAmbrDlBps();
    }

    public static String encodeUsageMonitor(long thresholdBytes) {
        return "key=" + PcrfService.MONITORING_KEY + ";threshold=" + thresholdBytes;
    }

    /** Compact JSON snapshot persisted to {@code epc_ipcan_session.decision_json}. */
    public static String toDecisionJson(PccDecision d) {
        StringBuilder b = new StringBuilder("{\"rules\":[");
        for (int i = 0; i < d.rules().size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append('"').append(escape(encodeRule(d.rules().get(i)))).append('"');
        }
        b.append("],\"usageMonitorThreshold\":")
                .append(d.usageMonitorThreshold() == null ? "null" : d.usageMonitorThreshold());
        b.append(",\"defaultBearerQos\":\"")
                .append(escape(encodeDefaultBearerQos(d.defaultBearerQos())))
                .append("\"}");
        return b.toString();
    }

    private static String bit(boolean v) {
        return v ? "1" : "0";
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}