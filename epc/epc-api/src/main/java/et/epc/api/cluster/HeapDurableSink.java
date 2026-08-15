package et.epc.api.cluster;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test / LOCAL durable sink with a read path. */
public final class HeapDurableSink implements DurableSink {

    private final Map<String, SessionCheckpoint> rows = new ConcurrentHashMap<>();

    @Override
    public void checkpoint(SessionCheckpoint session) {
        rows.put(session.sessionId(), session);
    }

    @Override
    public void forget(String sessionId) {
        rows.remove(sessionId);
    }

    @Override
    public Optional<SessionCheckpoint> load(String sessionId) {
        return Optional.ofNullable(rows.get(sessionId));
    }

    @Override
    public List<SessionCheckpoint> loadByOwner(String nodeId) {
        List<SessionCheckpoint> out = new CopyOnWriteArrayList<>();
        for (SessionCheckpoint cp : rows.values()) {
            if (cp.ownerNodeId().equals(nodeId)) {
                out.add(cp);
            }
        }
        return List.copyOf(out);
    }
}
