package et.epc.host;

import com.microjainslee.cluster.MarshallingAllowList;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.infinispan.Cache;
import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;

/**
 * Single embedded Infinispan manager for the EPC control plane. It is a
 * separate process from Elisa and must NEVER join {@code elisa-cluster} — the
 * EPC view is {@code epc-cluster}. Lab default {@code LOCAL}; multi-node
 * SGW-C/PGW-C peers set {@code epc.cluster.mode=DIST_SYNC}.
 */
@ApplicationScoped
public final class EpcInfinispanManager {

    private static final Logger LOG = LogManager.getLogger(EpcInfinispanManager.class);

    public static final String CACHE_SESSIONS = "epc-sessions";
    public static final String CACHE_SUBSCRIBERS = "epc-subscribers";
    public static final String CACHE_PCC = "epc-pcc";

    @ConfigProperty(name = "epc.cluster.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "epc.cluster.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "epc.cluster.name", defaultValue = "epc-cluster")
    String clusterName;

    @ConfigProperty(name = "epc.cluster.node-id", defaultValue = "epc-1")
    String nodeId;

    @ConfigProperty(name = "epc.cluster.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    private EmbeddedCacheManager manager;
    private boolean dist;

    @PostConstruct
    void start() {
        if (!enabled) {
            LOG.info("EpcInfinispanManager disabled");
            return;
        }
        requireEpcCluster(clusterName);
        dist = "DIST_SYNC".equalsIgnoreCase(mode) || "DIST".equalsIgnoreCase(mode);
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        String logical = (nodeId == null || nodeId.isBlank()) ? "epc-1" : nodeId;
        global.transport().nodeName(logical);
        global.cacheManagerName("epc-" + logical);
        if (dist) {
            global.transport().defaultTransport().clusterName(clusterName).nodeName(logical);
        } else {
            global.nonClusteredDefault();
            global.transport().nodeName(logical);
            global.cacheManagerName("epc-" + logical);
        }
        // Same marshaller as ClusterManager: JavaSerialization + allow list.
        var allow = global.serialization()
                .marshaller(new JavaSerializationMarshaller())
                .allowList();
        for (String regexp : MarshallingAllowList.REGEXPS) {
            allow.addRegexp(regexp);
        }
        allow.addRegexp("et\\.epc\\..*");
        manager = new DefaultCacheManager(global.build());
        defineCache(CACHE_SESSIONS, 100_000);
        defineCache(CACHE_SUBSCRIBERS, 200_000);
        defineCache(CACHE_PCC, 200_000);
        LOG.info("EpcInfinispanManager ready cluster={} mode={} ttl={}s caches=[{},{},{}]",
                dist ? clusterName : "(local)",
                dist ? "DIST_SYNC" : "LOCAL",
                ttlSeconds,
                CACHE_SESSIONS, CACHE_SUBSCRIBERS, CACHE_PCC);
    }

    private static void requireEpcCluster(String name) {
        if ("elisa-cluster".equals(name)) {
            throw new IllegalArgumentException("EPC must not join elisa-cluster");
        }
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
        return (nodeId == null || nodeId.isBlank()) ? "epc-1" : nodeId;
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