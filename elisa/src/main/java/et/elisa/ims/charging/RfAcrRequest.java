package et.elisa.ims.charging;

/**
 * Diameter Rf ACR STOP stub payload keyed by {@code icid} (grill G2).
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

    public static RfAcrRequest stop(String icid, String sipCallId, String account,
                                    Integer durationSec, String hangupCause) {
        return new RfAcrRequest(icid, sipCallId, account, durationSec, hangupCause, "STOP");
    }
}
