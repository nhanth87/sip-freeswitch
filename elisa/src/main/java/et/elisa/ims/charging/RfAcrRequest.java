package et.elisa.ims.charging;

/**
 * Diameter Rf ACR payload keyed by {@code icid} + {@code sipCallId} (grill G2).
 */
public record RfAcrRequest(
        String icid,
        String sipCallId,
        String account,
        Integer durationSec,
        String hangupCause,
        String recordType) {

    public RfAcrRequest {
        if (recordType == null || recordType.isBlank()) {
            recordType = "STOP";
        }
    }

    public static RfAcrRequest start(String icid, String sipCallId, String account) {
        return new RfAcrRequest(icid, sipCallId, account, null, null, "START");
    }

    public static RfAcrRequest stop(String icid, String sipCallId, String account,
                                    Integer durationSec, String hangupCause) {
        return new RfAcrRequest(icid, sipCallId, account, durationSec, hangupCause, "STOP");
    }
}
