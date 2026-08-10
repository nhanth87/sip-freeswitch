package et.elisa.ims.charging;

import java.time.Instant;

/**
 * Immutable payload for CGRates {@code CDRsV1.ProcessCDR} / {@code ProcessEvent}.
 * Account = msisdn preferred (soft impi). Correlation: icid + sipCallId.
 */
public record CgratesCdrEvent(
        String tenant,
        String account,
        String destination,
        String category,
        String source,
        String requestType,
        String usage,
        Instant answerTime,
        Instant setupTime,
        String icid,
        String sipCallId,
        String originId,
        String hangupCause) {

    public CgratesCdrEvent {
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
            requestType = "*pseudoprepaid";
        }
        if (usage == null || usage.isBlank()) {
            usage = "0s";
        }
    }

    public static String usageSeconds(Integer durationSec) {
        int s = durationSec == null || durationSec < 0 ? 0 : durationSec;
        return s + "s";
    }
}
