package et.elisa.ims.charging;

import java.util.List;
import java.util.Optional;

/**
 * Durable pending debit store (icid / sipCallId). Claim → debit → settle = at-most-once.
 */
public interface PendingChargeStore {

    /** Idempotent enqueue; returns existing row if key already present. */
    Optional<PendingCharge> enqueue(PendingCharge charge);

    Optional<PendingCharge> findByKey(String key);

    /** Update usage/destination on an existing PENDING row (BYE finalize). */
    boolean finalizeUsage(String key, String destination, String usage);

    /**
     * Atomically claim PENDING → CLAIMING for debit. Empty if missing/settled/already claimed.
     */
    Optional<PendingCharge> claim(String key);

    /** Mark settled once — returns false if already settled or missing. */
    boolean markSettled(String key);

    /** CLAIMING → PENDING after soft-fail debit (retry later). */
    boolean releaseClaim(String key);

    List<PendingCharge> listPending(int limit);
}
