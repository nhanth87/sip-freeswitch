package et.elisa.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.infinispan.Cache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * C1: Infinispan (shared {@link ElisaInfinispanManager}) for SIP dialog checkpoints.
 * RA {@code DialogRegistry} remains authoritative for in-flight SIP; this store
 * holds recoverable metadata for cluster failover / CMP restore.
 * Set {@code sf.cluster.infinispan.mode=DIST_SYNC} for multi-node.
 */
@ApplicationScoped
public class DialogCheckpointStore {

    private static final Logger LOG = LogManager.getLogger(DialogCheckpointStore.class);

    @Inject ElisaInfinispanManager cluster;

    private Cache<String, DialogCheckpoint> cache;
    private ConcurrentMap<String, DialogCheckpoint> memory;
    private volatile boolean useMemory;

    /** Unit-test / cross-node simulation — shared ConcurrentMap backend. */
    public DialogCheckpointStore memoryBackend() {
        this.useMemory = true;
        this.memory = new ConcurrentHashMap<>();
        this.cluster = null;
        return this;
    }

    /** Share an existing memory map (two nodes → one map = DIST_SYNC sim). */
    public DialogCheckpointStore shareMemory(ConcurrentMap<String, DialogCheckpoint> shared) {
        this.useMemory = true;
        this.memory = shared;
        this.cluster = null;
        return this;
    }

    @PostConstruct
    void start() {
        if (useMemory) {
            LOG.info("DialogCheckpointStore memory backend");
            return;
        }
        if (cluster == null || !cluster.enabled()) {
            LOG.info("DialogCheckpointStore disabled");
            return;
        }
        cache = cluster.cache(ElisaInfinispanManager.CACHE_DIALOGS);
        LOG.info("DialogCheckpointStore ready cache=%s mode=%s",
                ElisaInfinispanManager.CACHE_DIALOGS, cluster.modeLabel());
    }

    @PreDestroy
    void stop() {
        cache = null;
    }

    public void put(DialogCheckpoint cp) {
        if (cp == null || cp.callId() == null) {
            return;
        }
        if (useMemory && memory != null) {
            memory.put(cp.callId(), cp);
            return;
        }
        if (cache != null) {
            cache.put(cp.callId(), cp);
        }
    }

    public Optional<DialogCheckpoint> get(String callId) {
        if (callId == null) {
            return Optional.empty();
        }
        if (useMemory && memory != null) {
            return Optional.ofNullable(memory.get(callId));
        }
        if (cache == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(callId));
    }

    public void remove(String callId) {
        if (callId == null) {
            return;
        }
        if (useMemory && memory != null) {
            memory.remove(callId);
            return;
        }
        if (cache != null) {
            cache.remove(callId);
        }
    }

    public long size() {
        if (useMemory && memory != null) {
            return memory.size();
        }
        return cache == null ? 0 : cache.size();
    }

    public boolean enabled() {
        return (useMemory && memory != null) || cache != null;
    }

    /** Snapshot of live checkpoints (orphan scan / admin). Empty when disabled. */
    public java.util.Collection<DialogCheckpoint> entries() {
        if (useMemory && memory != null) {
            return java.util.List.copyOf(memory.values());
        }
        if (cache == null) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(cache.values());
    }
}
