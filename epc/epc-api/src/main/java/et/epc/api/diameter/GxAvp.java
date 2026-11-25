package et.epc.api.diameter;

/**
 * Gx AVP codes (TS 29.212 §5.3, TS 29.214 §5.3, RFC 4006 §8, RFC 7155), one table for the PCEF
 * (PGW-C) and the PCRF. Vendor-specific codes carry {@link #VENDOR_3GPP}.
 *
 * <p>Several codes used before were another AVP on the wire (Wireshark showed them by that
 * name): Default-EPS-Bearer-QoS is 1049 (1023 = Bearer-Control-Mode), Precedence 1010
 * (1017 = QoS-Negotiation), Max-Requested-Bandwidth-DL 515 (517 = Media-Component-Description),
 * Guaranteed-Bitrate-UL/DL 1026/1025 (518/519 = Media-Component-Number / Media-Sub-Component),
 * APN-Aggregate-Max-Bitrate-DL 1040 (1042 = Revalidation-Time).</p>
 */
public final class GxAvp {

    public static final long APP_ID = 16_777_238L;
    public static final int CC_CMD = 272;
    public static final int RA_CMD = 258;
    public static final long VENDOR_3GPP = 10415L;

    // Base / RFC 4006 / RFC 7155 (no vendor)
    public static final int USER_NAME = 1;
    public static final int FRAMED_IP_ADDRESS = 8;
    public static final int CALLED_STATION_ID = 30;
    public static final int AUTH_APPLICATION_ID = 258;
    public static final int RESULT_CODE = 268;
    public static final int CC_REQUEST_NUMBER = 415;
    public static final int CC_REQUEST_TYPE = 416;
    public static final int RATING_GROUP = 432;
    public static final int SERVICE_IDENTIFIER = 439;
    public static final int SUBSCRIPTION_ID = 443;
    public static final int SUBSCRIPTION_ID_DATA = 444;
    public static final int SUBSCRIPTION_ID_TYPE = 450;

    // TS 29.214 (3GPP)
    public static final int FLOW_DESCRIPTION = 507;
    public static final int FLOW_STATUS = 511;
    public static final int MAX_REQUESTED_BANDWIDTH_DL = 515;
    public static final int MAX_REQUESTED_BANDWIDTH_UL = 516;

    // TS 29.212 (3GPP)
    public static final int CHARGING_RULE_INSTALL = 1001;
    public static final int CHARGING_RULE_REMOVE = 1002;
    public static final int CHARGING_RULE_DEFINITION = 1003;
    public static final int CHARGING_RULE_NAME = 1005;
    public static final int OFFLINE = 1008;
    public static final int ONLINE = 1009;
    public static final int PRECEDENCE = 1010;
    public static final int QOS_INFORMATION = 1016;
    public static final int GUARANTEED_BITRATE_DL = 1025;
    public static final int GUARANTEED_BITRATE_UL = 1026;
    public static final int IP_CAN_TYPE = 1027;
    public static final int QOS_CLASS_IDENTIFIER = 1028;
    public static final int RAT_TYPE = 1032;
    public static final int ALLOCATION_RETENTION_PRIORITY = 1034;
    public static final int APN_AGGREGATE_MAX_BITRATE_DL = 1040;
    public static final int APN_AGGREGATE_MAX_BITRATE_UL = 1041;
    public static final int PRIORITY_LEVEL = 1046;
    public static final int PRE_EMPTION_CAPABILITY = 1047;
    public static final int PRE_EMPTION_VULNERABILITY = 1048;
    public static final int DEFAULT_EPS_BEARER_QOS = 1049;
    public static final int FLOW_INFORMATION = 1058;
    public static final int USAGE_MONITORING_INFORMATION = 1067;
    public static final int FLOW_DIRECTION = 1080;
    /** TS 29.061 3GPP-Charging-Id (vendor 10415, OctetString 4). */
    public static final int CHARGING_ID_3GPP = 2;

    // Values
    public static final int REQUEST_INITIAL = 1, REQUEST_UPDATE = 2, REQUEST_TERMINATION = 3;
    public static final int SUBSCRIPTION_END_USER_IMSI = 1;
    public static final int IP_CAN_3GPP_EPS = 5;
    public static final int RAT_EUTRAN = 1004;
    public static final int AUTH_SESSION_STATE = 277;

    private GxAvp() {}
}
