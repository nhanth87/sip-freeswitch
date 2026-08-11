package et.elisa.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
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
 * Single embedded Infinispan manager for Elisa N–N (grill I7 / C1).
 * All dialog / call / registration / owner caches share one JGroups cluster
 * ({@code sf.cluster.infinispan.cluster-name}) so DIST_SYNC peers see the same plane.
 * Lab default {@code LOCAL}; multi-node set {@code sf.cluster.infinispan.mode=DIST_SYNC}.
 */
@ApplicationScoped
public class ElisaInfinispanManager {

    private static final Logger LOG = Logger.getLogger(ElisaInfinispanManager.class);

    public static final String CACHE_DIALOGS = "elisa-dialog-checkpoints";
    public static final String CACHE_CALL_SESSIONS = "elisa-call-sessions";
    public static final String CACHE_REG_HOME = "elisa-reg-home";
    public static final String CACHE_DIALOG_OWNERS = "elisa-dialog-owners";

    @ConfigProperty(name = "sf.cluster.infinispan.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.cluster.infinispan.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "sf.cluster.infinispan.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    @ConfigProperty(name = "sf.cluster.infinispan.cluster-name", defaultValue = "elisa-cluster")
    String clusterName;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String nodeId;

    private EmbeddedCacheManager manager;
    private boolean dist;

    @PostConstruct
    void start() {
        if (!enabled) {
            LOG.info("ElisaInfinispanManager disabled");
            return;
        }
        dist = "DIST_SYNC".equalsIgnoreCase(mode) || "DIST".equalsIgnoreCase(mode);
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        String logical = (nodeId == null || nodeId.isBlank()) ? "elisa-1" : nodeId;
        global.transport().nodeName(logical);
        global.cacheManagerName("elisa-" + logical);
        if (dist) {
            global.transport().defaultTransport().clusterName(clusterName).nodeName(logical);
        } else {
            global.nonClusteredDefault();
            global.transport().nodeName(logical);
            global.cacheManagerName("elisa-" + logical);
        }
        manager = new DefaultCacheManager(global.build());
        defineCache(CACHE_DIALOGS, 100_000);
        defineCache(CACHE_CALL_SESSIONS, 200_000);
        defineCache(CACHE_REG_HOME, 200_000);
        defineCache(CACHE_DIALOG_OWNERS, 200_000);
        LOG.infof("ElisaInfinispanManager ready cluster=%s mode=%s ttl=%ds caches=[%s,%s,%s,%s]",
                dist ? clusterName : "(local)",
                dist ? "DIST_SYNC" : "LOCAL",
                ttlSeconds,
                CACHE_DIALOGS, CACHE_CALL_SESSIONS, CACHE_REG_HOME, CACHE_DIALOG_OWNERS);
    }

    private void defineCache(String name, long maxCount) {
        ConfigurationBuilder cb = new ConfigurationBuilder();
        cb.clustering().cacheMode(dist ? CacheMode.DIST_SYNC : CacheMode.LOCAL)
                .expiration().lifespan(ttlSeconds, TimeUnit.SECONDS)
                .memory().maxCount(maxCount);
        manager.defineConfiguration(name, cb.build());
    }

    @PreDestroy
    void stop() {
        if (manager != null) {
            manager.stop();
            manager = null;
        }
    }

    public boolean enabled() {
        return manager != null;
    }

    public boolean distSync() {
        return dist;
    }

    public String clusterName() {
        return clusterName;
    }

    public String nodeId() {
        return nodeId == null || nodeId.isBlank() ? "elisa-1" : nodeId;
    }

    public String modeLabel() {
        return dist ? "DIST_SYNC" : "LOCAL";
    }

    public EmbeddedCacheManager manager() {
        return manager;
    }

    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> cache(String name) {
        if (manager == null) {
            return null;
        }
        return (Cache<K, V>) manager.getCache(name);
    }
}
