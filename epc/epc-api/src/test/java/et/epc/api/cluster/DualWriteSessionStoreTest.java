package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import et.epc.api.BearerId;
import et.epc.api.Imsi;
import et.epc.api.SessionKeys;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class DualWriteSessionStoreTest {

    @Test
    void hotPathThenDurable() {
        HeapSessionStore hot = new HeapSessionStore();
        CopyOnWriteArrayList<String> sink = new CopyOnWriteArrayList<>();
        Executor same = Runnable::run;
        DualWriteSessionStore store = new DualWriteSessionStore(hot, new DurableSink() {
            @Override
            public void checkpoint(SessionCheckpoint session) {
                sink.add("put:" + session.sessionId());
            }

            @Override
            public void forget(String sessionId) {
                sink.add("del:" + sessionId);
            }

            @Override
            public Optional<SessionCheckpoint> load(String sessionId) {
                return Optional.empty();
            }

            @Override
            public List<SessionCheckpoint> loadByOwner(String nodeId) {
                return List.of();
            }
        }, same);
        SessionKeys keys = new SessionKeys(new Imsi("001010000000001"), "internet", new BearerId(5));
        String id = SessionCheckpoint.sgwId(keys);
        store.put(new SessionCheckpoint(
                id, "sgw", keys.imsi().digits(), keys.apn(), 5,
                "epc-1", 1L, "", null, null, null, 1L, "1", 1, 1L));
        store.remove(id);
        assertEquals(2, sink.size());
        assertEquals(0, store.size());
    }

    @Test
    void findByS11cTeidFallsBackToDurableNotH2() {
        HeapSessionStore hot = new HeapSessionStore();
        HeapDurableSink durable = new HeapDurableSink();
        DualWriteSessionStore store = new DualWriteSessionStore(hot, durable, Runnable::run);
        SessionKeys keys = new SessionKeys(new Imsi("001010000000001"), "internet", new BearerId(5));
        SessionCheckpoint cp = new SessionCheckpoint(
                SessionCheckpoint.sgwId(keys), "sgw", keys.imsi().digits(), keys.apn(), 5,
                "epc-1", 1L, "", null, null, null, 1L, "1", 0x11, 1L);
        durable.checkpoint(cp);
        assertEquals(0x11, store.findByS11cTeid(0x11).orElseThrow().s11cTeid());
    }
}
