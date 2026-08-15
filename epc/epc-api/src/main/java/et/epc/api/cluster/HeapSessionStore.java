package et.epc.api.cluster;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** LOCAL mode and unit tests. DIST_SYNC host wraps this with a durable sink. */
public final class HeapSessionStore implements SessionStore {
    private final ConcurrentHashMap<String, SessionCheckpoint> map = new ConcurrentHashMap<>();

    @Override
    public void put(SessionCheckpoint checkpoint) {
        map.put(checkpoint.sessionId(), checkpoint);
    }

    @Override
    public Optional<SessionCheckpoint> get(String sessionId) {
        return Optional.ofNullable(map.get(sessionId));
    }

    @Override
    public void remove(String sessionId) {
        map.remove(sessionId);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        for (SessionCheckpoint cp : map.values()) {
            if ("sgw".equals(cp.role()) && cp.s11cTeid() == s11cTeid) {
                return Optional.of(cp);
            }
        }
        return Optional.empty();
    }
}
