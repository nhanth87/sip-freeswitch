package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DualWriteSessionStorePropertiesTest {

    private static SessionCheckpoint cp(String id, int s11cTeid) {
        return new SessionCheckpoint(id, "sgw", "001010000000001", "internet", 5,
                "epc-1", 1L, "", null, null, null, 1L, "1", s11cTeid, 1L);
    }

    @Test
    void hotHitDoesNotHitDurable() {
        HeapSessionStore hot = new HeapSessionStore();
        AtomicInteger durablePuts = new AtomicInteger();
        DurableSink sink = new DurableSink() {
            @Override
            public void checkpoint(SessionCheckpoint s) {
                durablePuts.incrementAndGet();
            }

            @Override
            public void forget(String sessionId) {
            }

            @Override
            public Optional<SessionCheckpoint> load(String id) {
                throw new AssertionError("durable load must not be hit on hot hit");
            }

            @Override
            public java.util.List<SessionCheckpoint> loadByOwner(String nodeId) {
                return java.util.List.of();
            }
        };
        DualWriteSessionStore store = new DualWriteSessionStore(hot, sink, Runnable::run);
        store.put(cp("sgw|1", 11));
        assertTrue(store.get("sgw|1").isPresent());
        assertTrue(store.get("sgw|1").isPresent());
        assertEquals(1, durablePuts.get());
    }

    @Test
    void fallbackLoadsFromDurableAfterHotMiss() {
        HeapSessionStore hot = new HeapSessionStore();
        HeapDurableSink sink = new HeapDurableSink();
        DualWriteSessionStore store = new DualWriteSessionStore(hot, sink, Runnable::run);
        sink.checkpoint(cp("sgw|9", 12));
        assertTrue(store.get("sgw|9").isPresent());
    }

    @Test
    void removeForgetsDurable() {
        HeapSessionStore hot = new HeapSessionStore();
        HeapDurableSink sink = new HeapDurableSink();
        DualWriteSessionStore store = new DualWriteSessionStore(hot, sink, Runnable::run);
        store.put(cp("sgw|7", 13));
        store.remove("sgw|7");
        assertTrue(store.get("sgw|7").isEmpty());
        assertTrue(sink.load("sgw|7").isEmpty());
    }

    @Test
    void sizeReflectsHotOnly() {
        HeapSessionStore hot = new HeapSessionStore();
        HeapDurableSink sink = new HeapDurableSink();
        DualWriteSessionStore store = new DualWriteSessionStore(hot, sink, Runnable::run);
        store.put(cp("sgw|1", 11));
        store.put(cp("sgw|2", 12));
        assertEquals(2, store.size());
        store.remove("sgw|1");
        assertEquals(1, store.size());
    }

    @Test
    void findByS11cTeidFallsBack() {
        HeapSessionStore hot = new HeapSessionStore();
        HeapDurableSink sink = new HeapDurableSink();
        sink.checkpoint(cp("sgw|9", 0x22));
        DualWriteSessionStore store = new DualWriteSessionStore(hot, sink, Runnable::run);
        assertEquals(0x22, store.findByS11cTeid(0x22).orElseThrow().s11cTeid());
    }
}