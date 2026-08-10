package et.elisa.ims.charging;

import java.time.Instant;

/**
 * Durable pending debit key = icid preferred, else sipCallId (grill G3 — no double-debit).
 * Full SessionS reconcile is Phase C follow-up.
 */
public record PendingCharge(
        String key,
        String icid,
        String sipCallId,
        String account,
        String state,
        Instant enqueuedAt,
        Instant settledAt) {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_SETTLED = "SETTLED";

    public static String keyOf(String icid, String sipCallId) {
        if (icid != null && !icid.isBlank()) {
            return "icid:" + icid.trim();
        }
        if (sipCallId != null && !sipCallId.isBlank()) {
            return "sip:" + sipCallId.trim();
        }
        return null;
    }

    public static PendingCharge pending(String icid, String sipCallId, String account) {
        String key = keyOf(icid, sipCallId);
        return new PendingCharge(key, icid, sipCallId, account, STATE_PENDING, Instant.now(), null);
    }

    public PendingCharge settled() {
        return new PendingCharge(key, icid, sipCallId, account, STATE_SETTLED, enqueuedAt, Instant.now());
    }
}
