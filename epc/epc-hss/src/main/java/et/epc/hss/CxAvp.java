package et.epc.hss;

/**
 * TS 29.229 Cx/Dx AVP codes (Application-Id 16777216). Follows the same flat
 * {@code Map<Integer,String>} contract used by the Diameter seam (binary as hex).
 * The grouped SIP-Auth-Data-Item (612) is flattened to its leaf AVPs because
 * {@code ra-diameter} carries {@code Map<Integer,String>}, not nested groups.
 */
public final class CxAvp {

    /* Base diameter (RFC 6733) shared across applications. */
    public static final int RESULT_CODE = 268;
    public static final int EXPERIMENTAL_RESULT = 297;
    public static final int AUTH_SESSION_STATE = 277;

    /* Cx / Dx (TS 29.229). */
    public static final int USER_NAME = 1;
    public static final int VISITED_NETWORK_IDENTIFIER = 600;
    public static final int PUBLIC_IDENTITY = 601;
    public static final int SERVER_NAME = 602;
    public static final int SERVER_CAPABILITIES = 603;
    public static final int MANDATORY_CAPABILITY = 604;
    public static final int OPTIONAL_CAPABILITY = 605;
    public static final int USER_DATA = 606;
    public static final int SIP_NUMBER_AUTH_ITEMS = 607;
    public static final int SIP_AUTHENTICATION_SCHEME = 608;
    public static final int SIP_AUTHENTICATE = 609;
    public static final int SIP_AUTHORIZATION = 610;
    public static final int SIP_AUTHENTICATION_CONTEXT = 611;
    public static final int SIP_AUTH_DATA_ITEM = 612;
    public static final int SIP_ITEM_NUMBER = 613;
    public static final int SERVER_ASSIGNMENT_TYPE = 614;
    public static final int DEREGISTRATION_REASON = 615;
    public static final int REASON_CODE = 616;
    public static final int REASON_INFO = 617;
    public static final int CHARGING_INFORMATION = 618;
    public static final int PRIMARY_EVENT_CHARGING_FUNCTION_NAME = 619;
    public static final int SECONDARY_EVENT_CHARGING_FUNCTION_NAME = 620;
    public static final int PRIMARY_CHARGING_COLLECTION_FUNCTION_NAME = 621;
    public static final int SECONDARY_CHARGING_COLLECTION_FUNCTION_NAME = 622;
    public static final int USER_AUTHORIZATION_TYPE = 623;
    public static final int CONFIDENTIALITY_KEY = 625;
    public static final int INTEGRITY_KEY = 626;

    /* Result-Code / Experimental-Result-Code values. Cx UAA uses the 3GPP app
       experimental codes 2002 (FIRST_REGISTRATION) / 2003 (SUBSEQUENT_REGISTRATION)
       instead of DIAMETER_SUCCESS for S-CSCF parity. */
    public static final long SUCCESS = 2001L;
    public static final long FIRST_REGISTRATION = 2002L;
    public static final long SUBSEQUENT_REGISTRATION = 2003L;
    public static final long ERROR_USER_UNKNOWN = 5001L;
    public static final long ERROR_AUTHORIZATION_REJECTED = 5003L;
    public static final long UNABLE_TO_COMPLY = 5012L;

    /* Cx server-initiated command codes (TS 29.229) emitted as SendDiameterRequest. */
    public static final int RTR = 304;
    public static final int PPR = 305;

    /* Cx / Dx Application-Id (TS 29.229) for server-initiated requests. */
    public static final long APPLICATION_ID = 16_777_216L;

    /* SIP-Authentication-Scheme (TS 33.203). */
    public static final String SCHEME_AKA_V1_MD5 = "Digest-AKAv1-MD5";
    public static final String SCHEME_AKA_V2_MD5 = "Digest-AKAv2-MD5";

    /* Server-Assignment-Type values (TS 29.229). */
    public static final int SAT_NO_ASSIGNMENT = 0;
    public static final int SAT_REGISTRATION = 1;
    public static final int SAT_RE_REGISTRATION = 2;
    public static final int SAT_UNREGISTERED_USER = 3;
    public static final int SAT_TIMEOUT_DEREGISTRATION = 4;
    public static final int SAT_USER_DEREGISTRATION = 5;
    public static final int SAT_TIMEOUT_DEREGISTRATION_STORE_SERVER_NAME = 6;
    public static final int SAT_USER_DEREGISTRATION_STORE_SERVER_NAME = 7;
    public static final int SAT_ADMINISTRATIVE_DEREGISTRATION = 8;
    public static final int SAT_AUTHENTICATION_FAILURE = 9;
    public static final int SAT_AUTHENTICATION_TIMEOUT = 10;

    private CxAvp() {}
}