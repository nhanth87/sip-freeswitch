package et.epc.up.linux;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** LOCAL heap URR baseline store for labs/tests; production uses a PostgreSQL-backed impl. */
public final class HeapUrrDatastore implements UrrDatastore {

    private final ConcurrentMap<String, Long> baselines = new ConcurrentHashMap<>();

    @Override
    public void saveBaseline(String urrId, long baselineBytes) {
        Objects.requireNonNull(urrId, "urrId");
        if (baselineBytes < 0) {
            throw new IllegalArgumentException("baselineBytes must be non-negative");
        }
        baselines.put(urrId, baselineBytes);
    }

    @Override
    public Optional<Long> loadBaseline(String urrId) {
        return Optional.ofNullable(baselines.get(urrId));
    }

    @Override
    public void delete(String urrId) {
        baselines.remove(urrId);
    }
}