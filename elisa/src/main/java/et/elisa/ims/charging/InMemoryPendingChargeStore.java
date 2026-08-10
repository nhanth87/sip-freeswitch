package et.elisa.ims.charging;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory pending-charge store for Phase C scaffold (lab only — not Digicom HA).
 */
@ApplicationScoped
public class InMemoryPendingChargeStore implements PendingChargeStore {

    private final ConcurrentHashMap<String, PendingCharge> byKey = new ConcurrentHashMap<>();

    @Override
    public Optional<PendingCharge> enqueue(PendingCharge charge) {
        if (charge == null || charge.key() == null || charge.key().isBlank()) {
            return Optional.empty();
        }
        PendingCharge existing = byKey.putIfAbsent(charge.key(), charge);
        return Optional.of(existing == null ? charge : existing);
    }

    @Override
    public Optional<PendingCharge> findByKey(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(key));
    }

    @Override
    public boolean markSettled(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (;;) {
            PendingCharge cur = byKey.get(key);
            if (cur == null) {
                return false;
            }
            if (PendingCharge.STATE_SETTLED.equals(cur.state())) {
                return false;
            }
            PendingCharge next = cur.settled();
            if (byKey.replace(key, cur, next)) {
                return true;
            }
        }
    }

    @Override
    public List<PendingCharge> listPending(int limit) {
        int max = limit <= 0 ? 100 : limit;
        List<PendingCharge> out = new ArrayList<>();
        for (PendingCharge c : byKey.values()) {
            if (PendingCharge.STATE_PENDING.equals(c.state())) {
                out.add(c);
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    /** Test helper. */
    void clear() {
        byKey.clear();
    }
}
