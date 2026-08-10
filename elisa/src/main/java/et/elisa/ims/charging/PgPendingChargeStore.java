package et.elisa.ims.charging;

import et.elisa.persist.PendingChargeEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed pending debit store (N–N safe via PG). Soft-fails to empty on Panache errors in lab.
 */
@ApplicationScoped
public class PgPendingChargeStore implements PendingChargeStore {

    @Override
    @Transactional
    public Optional<PendingCharge> enqueue(PendingCharge charge) {
        if (charge == null || charge.key() == null || charge.key().isBlank()) {
            return Optional.empty();
        }
        PendingChargeEntity existing = PendingChargeEntity.findById(charge.key());
        if (existing != null) {
            return Optional.of(toRecord(existing));
        }
        PendingChargeEntity row = new PendingChargeEntity();
        row.chargeKey = charge.key();
        row.icid = charge.icid();
        row.sipCallId = charge.sipCallId();
        row.account = charge.account();
        row.destination = charge.destination();
        row.usageStr = charge.usage();
        row.state = PendingCharge.STATE_PENDING;
        row.enqueuedAt = charge.enqueuedAt() == null ? Instant.now() : charge.enqueuedAt();
        row.persist();
        return Optional.of(toRecord(row));
    }

    @Override
    public Optional<PendingCharge> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        PendingChargeEntity row = PendingChargeEntity.findById(key);
        return row == null ? Optional.empty() : Optional.of(toRecord(row));
    }

    @Override
    @Transactional
    public boolean finalizeUsage(String key, String destination, String usage) {
        PendingChargeEntity row = PendingChargeEntity.findById(key);
        if (row == null || PendingCharge.STATE_SETTLED.equals(row.state)) {
            return false;
        }
        row.destination = destination;
        row.usageStr = usage;
        return true;
    }

    @Override
    @Transactional
    public Optional<PendingCharge> claim(String key) {
        PendingChargeEntity row = PendingChargeEntity.findById(key);
        if (row == null || !PendingCharge.STATE_PENDING.equals(row.state)) {
            return Optional.empty();
        }
        row.state = PendingCharge.STATE_CLAIMING;
        return Optional.of(toRecord(row));
    }

    @Override
    @Transactional
    public boolean markSettled(String key) {
        PendingChargeEntity row = PendingChargeEntity.findById(key);
        if (row == null || PendingCharge.STATE_SETTLED.equals(row.state)) {
            return false;
        }
        row.state = PendingCharge.STATE_SETTLED;
        row.settledAt = Instant.now();
        return true;
    }

    @Override
    @Transactional
    public boolean releaseClaim(String key) {
        PendingChargeEntity row = PendingChargeEntity.findById(key);
        if (row == null || !PendingCharge.STATE_CLAIMING.equals(row.state)) {
            return false;
        }
        row.state = PendingCharge.STATE_PENDING;
        return true;
    }

    @Override
    public List<PendingCharge> listPending(int limit) {
        int max = limit <= 0 ? 100 : limit;
        List<PendingChargeEntity> rows = PendingChargeEntity
                .find("state", PendingCharge.STATE_PENDING)
                .page(0, max)
                .list();
        List<PendingCharge> out = new ArrayList<>(rows.size());
        for (PendingChargeEntity r : rows) {
            out.add(toRecord(r));
        }
        return List.copyOf(out);
    }

    static PendingCharge toRecord(PendingChargeEntity row) {
        return new PendingCharge(
                row.chargeKey,
                row.icid,
                row.sipCallId,
                row.account,
                row.destination,
                row.usageStr,
                row.state,
                row.enqueuedAt,
                row.settledAt);
    }
}
