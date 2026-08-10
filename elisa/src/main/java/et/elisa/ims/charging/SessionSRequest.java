package et.elisa.ims.charging;

import java.time.Instant;

/**
 * Immutable SessionS payload (Initiate / Update / Terminate). Account = msisdn preferred.
 */
public record SessionSRequest(
        String tenant,
        String account,
        String destination,
        String category,
        String source,
        String requestType,
        String usage,
        Instant setupTime,
        Instant answerTime,
        String icid,
        String sipCallId,
        String originId) {

    public SessionSRequest {
        if (tenant == null || tenant.isBlank()) {
            tenant = "cgrates.org";
        }
        if (category == null || category.isBlank()) {
            category = "call";
        }
        if (source == null || source.isBlank()) {
            source = "elisa";
        }
        if (requestType == null || requestType.isBlank()) {
            requestType = "*prepaid";
        }
        if (usage == null || usage.isBlank()) {
            usage = "60s";
        }
    }

    public static SessionSRequest invite(String tenant, String account, String destination,
                                         String icid, String sipCallId) {
        Instant now = Instant.now();
        return new SessionSRequest(tenant, account, destination, "call", "elisa", "*prepaid",
                "60s", now, now, icid, sipCallId, sipCallId);
    }

    public static SessionSRequest hangup(String tenant, String account, String destination,
                                         String icid, String sipCallId, Integer durationSec) {
        Instant now = Instant.now();
        return new SessionSRequest(tenant, account, destination, "call", "elisa", "*prepaid",
                CgratesCdrEvent.usageSeconds(durationSec), now, now, icid, sipCallId, sipCallId);
    }
}
