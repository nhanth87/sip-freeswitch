package et.elisa.ims.charging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diameter Rf ACR builder (TS 32.260 / 32.299 lab lite).
 * <p>
 * Honesty: AVPs are a flat {@code Map<Integer,String>} for {@code SendDiameterRequest}
 * — nested Service-Information / IMS-Information grouped AVPs are not binary-encoded.
 * ICID is carried as AVP 841 (IMS-Charging-Identifier) string value for correlation.
 */
public final class RfCodec {

    /** Diameter Accounting / Rf application id. */
    public static final long RF_APP_ID = 3L;
    /** Accounting-Request command code. */
    public static final int ACR = 271;

    public static final int USER_NAME = 1;
    public static final int ACCT_SESSION_ID = 44;
    public static final int ACCT_APPLICATION_ID = 259;
    public static final int ACCOUNTING_RECORD_TYPE = 480;
    public static final int ACCOUNTING_RECORD_NUMBER = 485;
    public static final int ACCT_SESSION_TIME = 46;
    /** 3GPP IMS-Charging-Identifier (lab string). */
    public static final int IMS_CHARGING_IDENTIFIER = 841;

    public static final int RECORD_EVENT = 1;
    public static final int RECORD_START = 2;
    public static final int RECORD_INTERIM = 3;
    public static final int RECORD_STOP = 4;

    private static final AtomicInteger RECORD_SEQ = new AtomicInteger(0);

    private RfCodec() {}

    public static Map<Integer, String> acrAvps(RfAcrRequest request) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(ACCT_APPLICATION_ID, Long.toString(RF_APP_ID));
        m.put(ACCOUNTING_RECORD_TYPE, Integer.toString(recordTypeCode(request.recordType())));
        m.put(ACCOUNTING_RECORD_NUMBER, Integer.toString(RECORD_SEQ.incrementAndGet()));
        if (request.account() != null && !request.account().isBlank()) {
            m.put(USER_NAME, request.account());
        }
        String sessionKey = request.sipCallId() != null && !request.sipCallId().isBlank()
                ? request.sipCallId()
                : request.icid();
        if (sessionKey != null && !sessionKey.isBlank()) {
            m.put(ACCT_SESSION_ID, sessionKey);
        }
        if (request.icid() != null && !request.icid().isBlank()) {
            m.put(IMS_CHARGING_IDENTIFIER, request.icid());
        }
        if (request.durationSec() != null && request.durationSec() >= 0
                && "STOP".equalsIgnoreCase(request.recordType())) {
            m.put(ACCT_SESSION_TIME, Integer.toString(request.durationSec()));
        }
        return m;
    }

    public static String sessionIdFor(RfAcrRequest request) {
        String icid = request == null ? null : request.icid();
        String callId = request == null ? null : request.sipCallId();
        if (icid != null && !icid.isBlank()) {
            return "rf;" + icid.trim();
        }
        if (callId != null && !callId.isBlank()) {
            return "rf;sip;" + callId.trim();
        }
        return "rf;anon";
    }

    static int recordTypeCode(String recordType) {
        if (recordType == null || recordType.isBlank()) {
            return RECORD_STOP;
        }
        return switch (recordType.trim().toUpperCase()) {
            case "START" -> RECORD_START;
            case "INTERIM" -> RECORD_INTERIM;
            case "EVENT" -> RECORD_EVENT;
            default -> RECORD_STOP;
        };
    }
}
