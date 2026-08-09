package et.elisa.recovery;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.logging.Logger;

/**
 * Infinispan (or memory) ownership fence for dialog correlates (R2/R3).
 * Dual-write with app CMP: put-if-absent on create; CAS claim on failover.
 */
@ApplicationScoped
public class DialogOwnerStore {

    private static final Logger LOG = Logger.getLogger(DialogOwnerStore.class);
    private static final String CACHE = "elisa-dialog-owners";

    @ConfigProperty(name = "sf.cluster.infinispan.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.cluster.infinispan.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "sf.cluster.infinispan.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String localNodeId;

    private EmbeddedCacheManager manager;
    private Cache<String, DialogOwner> cache;
    private final ConcurrentMap<String, DialogOwner> memory = new ConcurrentHashMap<>();
    private volatile boolean useMemoryOnly;

    /** Unit-test ctor — memory fence only. */
    public DialogOwnerStore() {
    }

    public DialogOwnerStore memoryOnly(String nodeId) {
        this.localNodeId = nodeId == null ? "elisa-1" : nodeId;
        this.useMemoryOnly = true;
        this.enabled = false;
        return this;
    }

    @PostConstruct
    void start() {
        if (useMemoryOnly || !enabled) {
            LOG.info("DialogOwnerStore memory/disabled mode");
            return;
        }
        boolean dist = "DIST_SYNC".equalsIgnoreCase(mode) || "DIST".equalsIgnoreCase(mode);
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        if (dist) {
            global.transport().defaultTransport().clusterName("elisa-dialog-owners");
        } else {
            global.nonClusteredDefault();
        }
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.clustering().cacheMode(dist ? CacheMode.DIST_SYNC : CacheMode.LOCAL)
                .expiration().lifespan(ttlSeconds, TimeUnit.SECONDS)
                .memory().maxCount(200_000);
        manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(CACHE, cb.build());
        cache = manager.getCache(CACHE);
        LOG.infof("DialogOwnerStore ready mode=%s node=%s", dist ? "DIST_SYNC" : "LOCAL", localNodeId);
    }

    @PreDestroy
    void stop() {
        if (manager != null) {
            manager.stop();
            manager = null;
            cache = null;
        }
    }

    public String localNodeId() {
        return localNodeId;
    }

    public boolean enabled() {
        return useMemoryOnly || cache != null;
    }

    /**
     * R2 — refuse claim when clustered transport has no members / manager stopped.
     * Memory and LOCAL mode always primary. Full DENY_READ_WRITES partition handling
     * can be layered when Elisa shares one ClusterManager with jainslee-cluster.
     */
    public boolean isPrimaryPartition() {
        if (cache == null || manager == null) {
            return true;
        }
        if (!manager.getCacheManagerConfiguration().isClustered()) {
            return true;
        }
        try {
            var members = manager.getMembers();
            return members != null && !members.isEmpty();
        } catch (RuntimeException ex) {
            LOG.debugf(ex, "membership check failed — refuse claim");
            return false;
        }
    }

    /** Initial ownership — put-if-absent (INVITE / first checkpoint). */
    public boolean putIfAbsent(String callId, String ownerNodeId) {
        if (callId == null || callId.isBlank() || ownerNodeId == null) {
            return false;
        }
        DialogOwner owner = new DialogOwner(callId, ownerNodeId, 0L, System.currentTimeMillis());
        if (cache != null) {
            return cache.putIfAbsent(callId, owner) == null;
        }
        return memory.putIfAbsent(callId, owner) == null;
    }

    public Optional<DialogOwner> get(String callId) {
        if (callId == null) {
            return Optional.empty();
        }
        DialogOwner o = cache != null ? cache.get(callId) : memory.get(callId);
        return Optional.ofNullable(o);
    }

    public void put(DialogOwner owner) {
        if (owner == null) {
            return;
        }
        if (cache != null) {
            cache.put(owner.callId(), owner);
        } else {
            memory.put(owner.callId(), owner);
        }
    }

    public void remove(String callId) {
        if (callId == null) {
            return;
        }
        if (cache != null) {
            cache.remove(callId);
        }
        memory.remove(callId);
    }

    /**
     * R3 fencing CAS: replace {@code expected} with owner={@code newOwnerNodeId},
     * generation={@code expected.generation()+1}. Loser returns false (NOOP).
     */
    public boolean tryClaim(DialogOwner expected, String newOwnerNodeId) {
        if (expected == null || newOwnerNodeId == null || !isPrimaryPartition()) {
            return false;
        }
        long now = System.currentTimeMillis();
        DialogOwner next = expected.withOwner(newOwnerNodeId, expected.generation() + 1, now);
        if (cache != null) {
            return cache.replace(expected.callId(), expected, next);
        }
        return memory.replace(expected.callId(), expected, next);
    }

    /**
     * Claim by expected generation when the caller only has the fence number
     * (failoverSpawn API).
     */
    public boolean tryClaim(String callId, String newOwnerNodeId, long expectedGeneration) {
        Optional<DialogOwner> cur = get(callId);
        if (cur.isEmpty()) {
            DialogOwner created = new DialogOwner(callId, newOwnerNodeId, expectedGeneration + 1,
                    System.currentTimeMillis());
            if (cache != null) {
                return cache.putIfAbsent(callId, created) == null;
            }
            return memory.putIfAbsent(callId, created) == null;
        }
        DialogOwner owner = cur.get();
        if (owner.generation() != expectedGeneration) {
            return false;
        }
        return tryClaim(owner, newOwnerNodeId);
    }

    public List<DialogOwner> listOwnedBy(String ownerNodeId) {
        List<DialogOwner> out = new ArrayList<>();
        if (ownerNodeId == null) {
            return out;
        }
        Consumer<DialogOwner> collect = o -> {
            if (ownerNodeId.equals(o.ownerNodeId())) {
                out.add(o);
            }
        };
        if (cache != null) {
            cache.values().forEach(collect);
        } else {
            memory.values().forEach(collect);
        }
        return out;
    }

    public List<DialogOwner> listAll() {
        if (cache != null) {
            return new ArrayList<>(cache.values());
        }
        return new ArrayList<>(memory.values());
    }
}
