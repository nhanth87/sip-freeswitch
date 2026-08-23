package et.epc.host;

import et.epc.pcrf.PccCache;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PolicyRepository;
import java.util.List;
import java.util.Objects;
import org.infinispan.Cache;

/**
 * {@link PccCache} backed by the shared Infinispan {@code epc-pcc} cache.
 * Read-through from the durable {@link PolicyRepository} on a miss; decisions
 * are non-secret only (no Ki/OPc or subscription secrets ever enter this cache).
 */
public final class IspnPccCache implements PccCache {

    private final Cache<String, List<PccRule>> cache;
    private final PolicyRepository repo;

    public IspnPccCache(Cache<String, List<PccRule>> cache) {
        this(cache, null);
    }

    public IspnPccCache(Cache<String, List<PccRule>> cache, PolicyRepository repo) {
        this.cache = Objects.requireNonNull(cache);
        this.repo = repo;
    }

    @Override
    public List<PccRule> rulesFor(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        return cache.computeIfAbsent(PccCache.key(imsi, apn),
                k -> repo == null ? List.of() : safe(repo.loadRules(imsi, apn)));
    }

    @Override
    public void putRules(String imsi, String apn, List<PccRule> rules) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        cache.put(PccCache.key(imsi, apn), rules == null ? List.of() : List.copyOf(rules));
    }

    /**
     * Admin-write invalidation hook: drop the cached rules for one {@code (imsi, apn)}
     * decision input so the next {@link #rulesFor} re-reads the durable
     * {@link PolicyRepository}. Wired by the admin policy-write path (the PCRF SBB
     * owner triggers it after mutating {@code epc_pcc_rule}); RAR push rides on top.
     */
    @Override
    public void invalidate(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        cache.remove(PccCache.key(imsi, apn));
    }

    private static List<PccRule> safe(List<PccRule> rules) {
        return rules == null ? List.of() : List.copyOf(rules);
    }
}