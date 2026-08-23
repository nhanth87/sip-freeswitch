package et.epc.pcrf;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-heap read-through cache. Read-through only when a {@link PolicyRepository}
 * is supplied; otherwise it is a pure cache and misses return an empty list.
 */
public final class HeapPccCache implements PccCache {

    private final ConcurrentHashMap<String, List<PccRule>> map = new ConcurrentHashMap<>();
    private final PolicyRepository repo;

    public HeapPccCache() {
        this(null);
    }

    public HeapPccCache(PolicyRepository repo) {
        this.repo = repo;
    }

    @Override
    public List<PccRule> rulesFor(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        return map.computeIfAbsent(PccCache.key(imsi, apn),
                k -> repo == null ? List.of() : safe(repo.loadRules(imsi, apn)));
    }

    @Override
    public void putRules(String imsi, String apn, List<PccRule> rules) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        map.put(PccCache.key(imsi, apn), rules == null ? List.of() : List.copyOf(rules));
    }

    @Override
    public void invalidate(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        map.remove(PccCache.key(imsi, apn));
    }

    private static List<PccRule> safe(List<PccRule> rules) {
        return rules == null ? List.of() : List.copyOf(rules);
    }
}