package et.elisa.cluster;

import et.elisa.cmp.SipCallIds;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.infinispan.Cache;
import org.jboss.logging.Logger;

/** P2 hot map: keyed by fs_uuid and sip_call_id (dual keys → same CallSession). */
@ApplicationScoped
public class CallSessionStore {

    private static final Logger LOG = Logger.getLogger(CallSessionStore.class);

    @Inject ElisaInfinispanManager cluster;

    private Cache<String, CallSession> cache;

    @PostConstruct
    void start() {
        if (cluster == null || !cluster.enabled()) {
            return;
        }
        cache = cluster.cache(ElisaInfinispanManager.CACHE_CALL_SESSIONS);
        LOG.infof("CallSessionStore ready mode=%s", cluster.modeLabel());
    }

    @PreDestroy
    void stop() {
        cache = null;
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
        String canon = SipCallIds.canonical(sipCallId);
        CallSession hit = cache.get("sip:" + canon);
        if (hit != null) {
            return Optional.of(hit);
        }
        if (!canon.equals(sipCallId)) {
            return Optional.ofNullable(cache.get("sip:" + sipCallId));
        }
        return Optional.empty();
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
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<CallSession> out = new ArrayList<>();
        for (CallSession s : cache.values()) {
            if (s == null || s.fsUuid() == null || !seen.add(s.fsUuid())) {
                continue;
            }
            out.add(s);
        }
        return out;
    }

    public boolean enabled() {
        return cache != null;
    }
}
