package et.epc.api.cluster;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Heap/Infinispan first, then durable PG on a side executor — same dual-write
 * shape as Elisa {@code CallSessionStore}.
 */
public final class DualWriteSessionStore implements SessionStore {

    private final SessionStore hot;
    private final DurableSink durable;
    private final Executor offHotPath;

    public DualWriteSessionStore(SessionStore hot, DurableSink durable, Executor offHotPath) {
        this.hot = Objects.requireNonNull(hot);
        this.durable = Objects.requireNonNull(durable);
        this.offHotPath = Objects.requireNonNull(offHotPath);
    }

    @Override
    public void put(SessionCheckpoint checkpoint) {
        hot.put(checkpoint);
        offHotPath.execute(() -> durable.checkpoint(checkpoint));
    }

    @Override
    public Optional<SessionCheckpoint> get(String sessionId) {
        Optional<SessionCheckpoint> hotHit = hot.get(sessionId);
        if (hotHit.isPresent()) {
            return hotHit;
        }
        return durable.load(sessionId);
    }

    @Override
    public void remove(String sessionId) {
        hot.remove(sessionId);
        offHotPath.execute(() -> durable.forget(sessionId));
    }

    @Override
    public int size() {
        return hot.size();
    }

    @Override
    public Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        Optional<SessionCheckpoint> hotHit = hot.findByS11cTeid(s11cTeid);
        if (hotHit.isPresent()) {
            return hotHit;
        }
        return durable.findByS11cTeid(s11cTeid);
    }
}
