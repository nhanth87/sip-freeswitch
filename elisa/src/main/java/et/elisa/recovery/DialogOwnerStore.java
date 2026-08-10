package et.elisa.recovery;

import et.elisa.cluster.ElisaInfinispanManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.jboss.logging.Logger;

/**
 * Infinispan (or memory) ownership fence for dialog correlates (R2/R3).
 * Dual-write with app CMP: put-if-absent on create; CAS claim on failover.
 * Shares {@link ElisaInfinispanManager} cluster name with dialog/call/reg caches.
 */
@ApplicationScoped
public class DialogOwnerStore {

    private static final Logger LOG = Logger.getLogger(DialogOwnerStore.class);

    @Inject ElisaInfinispanManager cluster;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String localNodeId;

    private Cache<String, DialogOwner> cache;
    private final ConcurrentMap<String, DialogOwner> memory = new ConcurrentHashMap<>();
    private volatile boolean useMemoryOnly;

    /** Unit-test ctor — memory fence only. */
    public DialogOwnerStore() {
    }

    public DialogOwnerStore memoryOnly(String nodeId) {
        this.localNodeId = nodeId == null ? "elisa-1" : nodeId;
        this.useMemoryOnly = true;
        this.cluster = null;
        return this;
    }

    @PostConstruct
    void start() {
        if (useMemoryOnly) {
            LOG.info("DialogOwnerStore memory mode");
            return;
        }
        if (cluster == null || !cluster.enabled()) {
            LOG.info("DialogOwnerStore disabled — memory fence only");
            return;
        }
        cache = cluster.cache(ElisaInfinispanManager.CACHE_DIALOG_OWNERS);
        LOG.infof("DialogOwnerStore ready mode=%s node=%s", cluster.modeLabel(), localNodeId);
    }

    @PreDestroy
    void stop() {
        cache = null;
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
        if (cache == null || cluster == null || cluster.manager() == null) {
            return true;
        }
        var manager = cluster.manager();
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
