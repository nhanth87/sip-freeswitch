package et.epc.host;

import et.epc.api.cluster.SessionCheckpoint;
import et.epc.api.cluster.SessionStore;
import java.util.Objects;
import java.util.Optional;
import org.infinispan.Cache;

/**
 * {@link SessionStore} backed by the shared Infinispan {@code epc-sessions}
 * cache. S11-C TEID lookup scans values (same shape as {@code HeapSessionStore})
 * and is only meaningful for {@code sgw} checkpoints.
 */
public final class IspnSessionStore implements SessionStore {

    private final Cache<String, SessionCheckpoint> cache;

    public IspnSessionStore(Cache<String, SessionCheckpoint> cache) {
        this.cache = Objects.requireNonNull(cache);
    }

    @Override
    public void put(SessionCheckpoint checkpoint) {
        cache.put(checkpoint.sessionId(), checkpoint);
    }

    @Override
    public Optional<SessionCheckpoint> get(String sessionId) {
        return Optional.ofNullable(cache.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        cache.remove(sessionId);
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        for (SessionCheckpoint cp : cache.values()) {
            if ("sgw".equals(cp.role()) && cp.s11cTeid() == s11cTeid) {
                return Optional.of(cp);
            }
        }
        return Optional.empty();
    }
}