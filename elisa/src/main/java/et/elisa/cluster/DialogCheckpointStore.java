package et.elisa.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.logging.Logger;

/**
 * C1: Infinispan embedded cache for SIP dialog checkpoints.
 * RA {@code DialogRegistry} remains authoritative for in-flight SIP; this store
 * holds recoverable metadata for cluster failover / ops visibility.
 * Default mode LOCAL (lab single-node). Set {@code sf.cluster.infinispan.mode=DIST_SYNC}
 * for multi-node (JGroups).
 */
@ApplicationScoped
public class DialogCheckpointStore {

    private static final Logger LOG = Logger.getLogger(DialogCheckpointStore.class);
    private static final String CACHE = "elisa-dialog-checkpoints";

    @ConfigProperty(name = "sf.cluster.infinispan.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.cluster.infinispan.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    @ConfigProperty(name = "sf.cluster.infinispan.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "sf.cluster.infinispan.cluster-name", defaultValue = "elisa-dialogs")
    String clusterName;

    private EmbeddedCacheManager manager;
    private Cache<String, DialogCheckpoint> cache;

    @PostConstruct
    void start() {
        if (!enabled) {
            LOG.info("DialogCheckpointStore disabled");
            return;
        }
        boolean dist = "DIST_SYNC".equalsIgnoreCase(mode) || "DIST".equalsIgnoreCase(mode);
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        if (dist) {
            global.transport().defaultTransport().clusterName(clusterName);
        } else {
            global.nonClusteredDefault();
        }
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.clustering().cacheMode(dist ? CacheMode.DIST_SYNC : CacheMode.LOCAL)
                .expiration().lifespan(ttlSeconds, TimeUnit.SECONDS)
                .memory().maxCount(100_000);
        manager = new DefaultCacheManager(global.build());
        manager.defineConfiguration(CACHE, cb.build());
        cache = manager.getCache(CACHE);
        LOG.infof("DialogCheckpointStore ready cache=%s mode=%s ttl=%ds",
                CACHE, dist ? "DIST_SYNC" : "LOCAL", ttlSeconds);
    }

    @PreDestroy
    void stop() {
        if (manager != null) {
            manager.stop();
            manager = null;
            cache = null;
        }
    }

    public void put(DialogCheckpoint cp) {
        if (cache == null || cp == null || cp.callId() == null) {
            return;
        }
        cache.put(cp.callId(), cp);
    }

    public Optional<DialogCheckpoint> get(String callId) {
        if (cache == null || callId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(callId));
    }

    public void remove(String callId) {
        if (cache == null || callId == null) {
            return;
        }
        cache.remove(callId);
    }

    public long size() {
        return cache == null ? 0 : cache.size();
    }

    public boolean enabled() {
        return cache != null;
    }

    /** Snapshot of live checkpoints (orphan scan / admin). Empty when disabled. */
    public java.util.Collection<DialogCheckpoint> entries() {
        if (cache == null) {
            return java.util.List.of();
        }
        return java.util.List.copyOf(cache.values());
    }
}
