package et.epc.host;

import et.epc.api.cluster.SessionCheckpoint;
import et.epc.api.cluster.SessionStore;
import java.util.Objects;
import java.util.Optional;
import org.infinispan.Cache;

/**
 * {@link SessionStore} backed by the shared Infinispan {@code epc-sessions} cache.
 * TEID lookups go through {@code epc-session-index} ({@code s11|<teid>} / {@code s5c|<teid>}
 * → session id): in {@code DIST_SYNC} a scan of {@code values()} would walk every node's
 * segment on each S11 / S5-C request. TEIDs carry the node index in their top byte, so keys
 * never collide across nodes.
 */
public final class IspnSessionStore implements SessionStore {

    private final Cache<String, SessionCheckpoint> cache;
    private final Cache<String, String> index;

    public IspnSessionStore(Cache<String, SessionCheckpoint> cache) {
        this(cache, null);
    }

    public IspnSessionStore(Cache<String, SessionCheckpoint> cache, Cache<String, String> index) {
        this.cache = Objects.requireNonNull(cache);
        this.index = index;
    }

    static String s11Key(int teid) {
        return "s11|" + Integer.toHexString(teid);
    }

    static String s5cKey(int teid) {
        return "s5c|" + Integer.toHexString(teid);
    }

    @Override
    public void put(SessionCheckpoint checkpoint) {
        cache.put(checkpoint.sessionId(), checkpoint);
        if (index != null) {
            if ("sgw".equals(checkpoint.role()) && checkpoint.s11cTeid() != 0) {
                index.put(s11Key(checkpoint.s11cTeid()), checkpoint.sessionId());
            }
            if ("pgw".equals(checkpoint.role()) && checkpoint.s5cTeid() != 0) {
                index.put(s5cKey(checkpoint.s5cTeid()), checkpoint.sessionId());
            }
        }
    }

    @Override
    public Optional<SessionCheckpoint> get(String sessionId) {
        return Optional.ofNullable(cache.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        SessionCheckpoint old = cache.remove(sessionId);
        if (index != null && old != null) {
            if (old.s11cTeid() != 0) {
                index.remove(s11Key(old.s11cTeid()), sessionId);
            }
            if (old.s5cTeid() != 0) {
                index.remove(s5cKey(old.s5cTeid()), sessionId);
            }
        }
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        if (index != null) {
            return byIndex(s11Key(s11cTeid));
        }
        for (SessionCheckpoint cp : cache.values()) {
            if ("sgw".equals(cp.role()) && cp.s11cTeid() == s11cTeid) {
                return Optional.of(cp);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<SessionCheckpoint> findByS5cTeid(int s5cTeid) {
        if (index != null) {
            return byIndex(s5cKey(s5cTeid));
        }
        for (SessionCheckpoint cp : cache.values()) {
            if ("pgw".equals(cp.role()) && s5cTeid != 0 && cp.s5cTeid() == s5cTeid) {
                return Optional.of(cp);
            }
        }
        return Optional.empty();
    }

    private Optional<SessionCheckpoint> byIndex(String key) {
        String id = index.get(key);
        return id == null ? Optional.empty() : Optional.ofNullable(cache.get(id));
    }
}
