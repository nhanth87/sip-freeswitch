package et.elisa.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.infinispan.Cache;
import org.jboss.logging.Logger;

/** C1/C4: IMPU → home S-CSCF node id (registration sticky). Shared cluster plane. */
@ApplicationScoped
public class RegistrationHomeStore {

    private static final Logger LOG = Logger.getLogger(RegistrationHomeStore.class);

    @Inject ElisaInfinispanManager cluster;

    private Cache<String, String> cache;

    @PostConstruct
    void start() {
        if (cluster == null || !cluster.enabled()) {
            return;
        }
        cache = cluster.cache(ElisaInfinispanManager.CACHE_REG_HOME);
        LOG.infof("RegistrationHomeStore ready mode=%s", cluster.modeLabel());
    }

    @PreDestroy
    void stop() {
        cache = null;
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

    public boolean enabled() {
        return cache != null;
    }
}
