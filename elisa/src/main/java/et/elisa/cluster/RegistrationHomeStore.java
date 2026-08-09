package et.elisa.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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

/** C1/C4: IMPU → home S-CSCF node id (registration sticky). */
@ApplicationScoped
public class RegistrationHomeStore {

    private static final Logger LOG = Logger.getLogger(RegistrationHomeStore.class);
    private static final String CACHE = "elisa-reg-home";

    @ConfigProperty(name = "sf.cluster.infinispan.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.cluster.infinispan.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "sf.cluster.infinispan.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    private EmbeddedCacheManager manager;
    private Cache<String, String> cache;

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        boolean dist = "DIST_SYNC".equalsIgnoreCase(mode) || "DIST".equalsIgnoreCase(mode);
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        if (dist) {
            global.transport().defaultTransport().clusterName("elisa-reg-home");
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
        LOG.infof("RegistrationHomeStore ready mode=%s", dist ? "DIST_SYNC" : "LOCAL");
    }

    @PreDestroy
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    public void put(String impu, String homeNode) {
        if (cache != null && impu != null && homeNode != null) {
            cache.put(impu, homeNode);
        }
    }

    public Optional<String> get(String impu) {
        if (cache == null || impu == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get(impu));
    }

    public void remove(String impu) {
        if (cache != null && impu != null) {
            cache.remove(impu);
        }
    }
}
