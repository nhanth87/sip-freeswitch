package et.elisa.ims.charging;

import java.time.Instant;

/**
 * Durable pending debit key = icid preferred, else sipCallId (grill G3 — no double-debit).
 */
public record PendingCharge(
        String key,
        String icid,
        String sipCallId,
        String account,
        String destination,
        String usage,
        String state,
        Instant enqueuedAt,
        Instant settledAt) {

    public static final String STATE_PENDING = "PENDING";
    public static final String STATE_CLAIMING = "CLAIMING";
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
        return pending(icid, sipCallId, account, null, null);
    }

    public static PendingCharge pending(String icid, String sipCallId, String account,
                                        String destination, String usage) {
        String key = keyOf(icid, sipCallId);
        return new PendingCharge(key, icid, sipCallId, account, destination, usage,
                STATE_PENDING, Instant.now(), null);
    }

    public PendingCharge withUsage(String destination, String usage) {
        return new PendingCharge(key, icid, sipCallId, account, destination, usage,
                state, enqueuedAt, settledAt);
    }

    public PendingCharge claiming() {
        return new PendingCharge(key, icid, sipCallId, account, destination, usage,
                STATE_CLAIMING, enqueuedAt, null);
    }

    public PendingCharge settled() {
        return new PendingCharge(key, icid, sipCallId, account, destination, usage,
                STATE_SETTLED, enqueuedAt, Instant.now());
    }

    public PendingCharge releaseToPending() {
        return new PendingCharge(key, icid, sipCallId, account, destination, usage,
                STATE_PENDING, enqueuedAt, null);
    }
}
