package et.epc.hss;

public final class S6aAvp {
    public static final int USER_NAME = 1;
    public static final int RESULT_CODE = 268;
    public static final int AUTH_SESSION_STATE = 277;
    public static final int FAILED_AVP = 279;
    public static final int ORIGIN_REALM = 296;
    public static final int ORIGIN_HOST = 264;
    public static final int VISITED_PLMN_ID = 1407;
    public static final int RE_SYNCHRONIZATION_INFO = 1411;
    public static final int RAND = 1447;
    public static final int XRES = 1448;
    public static final int AUTN = 1449;
    public static final int KASME = 1450;
    public static final int SUBSCRIPTION_DATA = 1400;
    public static final int AUTHENTICATION_INFO = 1490;
    public static final int E_UTRAN_VECTOR = 1491;
    public static final int ULA_FLAGS = 1406;
    public static final int MSISDN = 701;
    public static final int AMBR = 1435;
    public static final int MAX_REQUESTED_BANDWIDTH_UL = 516;
    public static final int MAX_REQUESTED_BANDWIDTH_DL = 517;
    public static final int CANCELLATION_TYPE = 1420;
    public static final int IDR_FLAGS = 1525;
    public static final int PURGE_UE = 1472;

    /* Subscription-Data leaf/grouped AVPs (TS 29.272). */
    public static final int SUBSCRIBER_STATUS = 1424;
    public static final int NETWORK_ACCESS_MODE = 1417;
    public static final int OPERATOR_DETERMINED_BARRING = 1425;
    public static final int ACCESS_RESTRICTION_DATA = 1426;
    public static final int SUBSCRIBED_PERIODIC_RAU_TAU_TIMER = 1619;

    /* APN-Configuration-Profile (TS 29.272 7.3.34/7.3.35/7.3.37). */
    public static final int APN_CONFIGURATION_PROFILE = 1429;
    public static final int CONTEXT_IDENTIFIER = 1423;
    public static final int ALL_APN_CONFIGURATIONS_INCLUDED_INDICATOR = 1428;
    public static final int APN_CONFIGURATION = 1430;
    public static final int PDN_TYPE = 1456;
    public static final int SERVICE_SELECTION = 493;
    public static final int EPS_SUBSCRIBED_QOS_PROFILE = 1431;
    public static final int QOS_CLASS_IDENTIFIER = 1028;
    public static final int ALLOCATION_RETENTION_PRIORITY = 1034;
    public static final int PRIORITY_LEVEL = 1046;
    public static final int PRE_EMPTION_CAPABILITY = 1047;
    public static final int PRE_EMPTION_VULNERABILITY = 1048;

    public static final long SUCCESS = 2001L;
    public static final long AUTHENTICATION_DATA_UNAVAILABLE = 4181L;
    public static final long ERROR_USER_UNKNOWN = 5001L;
    public static final long INVALID_AVP_VALUE = 5004L;
    public static final long MISSING_AVP = 5005L;
    /** DIAMETER_UNABLE_TO_COMPLY (RFC 6733 3002) — the peer-trust drawbridge refusal. */
    public static final long ERROR_UNKNOWN_PEER = 3002L;

    private S6aAvp() {}
}
