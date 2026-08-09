package et.elisa.cluster;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
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

/** P2 hot map: keyed by fs_uuid and sip_call_id (dual keys → same CallSession). */
@ApplicationScoped
public class CallSessionStore {

    private static final Logger LOG = Logger.getLogger(CallSessionStore.class);
    private static final String CACHE = "elisa-call-sessions";

    @ConfigProperty(name = "sf.cluster.infinispan.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.cluster.infinispan.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "sf.cluster.infinispan.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    private EmbeddedCacheManager manager;
    private Cache<String, CallSession> cache;

    @PostConstruct
    void start() {
        if (!enabled) {
            return;
        }
        boolean dist = "DIST_SYNC".equalsIgnoreCase(mode) || "DIST".equalsIgnoreCase(mode);
        GlobalConfigurationBuilder global = new GlobalConfigurationBuilder();
        if (dist) {
            global.transport().defaultTransport().clusterName("elisa-call-sessions");
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
        LOG.infof("CallSessionStore ready mode=%s", dist ? "DIST_SYNC" : "LOCAL");
    }

    @PreDestroy
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    public void put(CallSession session) {
        if (cache == null || session == null || session.fsUuid() == null) {
            return;
        }
        cache.put("fs:" + session.fsUuid(), session);
        if (session.sipCallId() != null && !session.sipCallId().isBlank()) {
            cache.put("sip:" + session.sipCallId(), session);
        }
    }

    public Optional<CallSession> byFsUuid(String fsUuid) {
        if (cache == null || fsUuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get("fs:" + fsUuid));
    }

    public Optional<CallSession> bySipCallId(String sipCallId) {
        if (cache == null || sipCallId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.get("sip:" + sipCallId));
    }

    public void remove(CallSession session) {
        if (cache == null || session == null) {
            return;
        }
        if (session.fsUuid() != null) {
            cache.remove("fs:" + session.fsUuid());
        }
        if (session.sipCallId() != null) {
            cache.remove("sip:" + session.sipCallId());
        }
    }

    public List<CallSession> listLive() {
        if (cache == null) {
            return List.of();
        }
        Collection<CallSession> values = cache.values();
        List<CallSession> out = new ArrayList<>();
        for (CallSession s : values) {
            if (s != null && s.fsUuid() != null) {
                // de-dupe: only include fs: keys conceptually — values may appear twice
                boolean seen = false;
                for (CallSession e : out) {
                    if (s.fsUuid().equals(e.fsUuid())) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    public boolean enabled() {
        return cache != null;
    }
}
