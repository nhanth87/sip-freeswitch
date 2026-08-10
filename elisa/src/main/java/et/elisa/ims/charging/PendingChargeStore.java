package et.elisa.ims.charging;

import java.util.List;
import java.util.Optional;

/**
 * Phase C scaffold — durable pending debit store (icid / sipCallId). Postgres back-end later.
 */
public interface PendingChargeStore {

    /** Idempotent enqueue; returns existing row if key already present. */
    Optional<PendingCharge> enqueue(PendingCharge charge);

    Optional<PendingCharge> findByKey(String key);

    /** Mark settled once — returns false if already settled or missing (at-most-once debit). */
    boolean markSettled(String key);

    List<PendingCharge> listPending(int limit);
}
