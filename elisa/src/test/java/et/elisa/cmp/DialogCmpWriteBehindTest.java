package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** PG copy of the CMP leaves the SBB thread: coalesced, read-your-writes, tombstoned. */
class DialogCmpWriteBehindTest {

    /** Fake PG: records batches, counts direct (synchronous) calls. */
    static final class FakePg extends DialogCmpDurableWriter {
        final List<DialogCheckpoint> upserts = new ArrayList<>();
        final List<String> ended = new ArrayList<>();
        int syncCalls;
        int loads;

        @Override
        public void persist(DialogCheckpoint cp) {
            syncCalls++;
        }

        @Override
        public void markEnded(String sipCallId) {
            syncCalls++;
        }

        @Override
        public void writeBatch(List<DialogCheckpoint> u, List<String> e) {
            upserts.addAll(u);
            ended.addAll(e);
        }

        @Override
        public Optional<InviteCmpSnapshot> load(String sipCallId) {
            loads++;
            return Optional.empty();
        }
    }

    private static InviteCmpSnapshot snap(String callId, String state) {
        long now = System.currentTimeMillis();
        return new InviteCmpSnapshot(callId, null, null, "sip:a@ims.lab", "sip:1000@ims.lab",
                "sip:1000@127.0.0.1:5062", null, "fs-1", "elisa-1", state, now, now);
    }

    private static DialogCmpPersistence store(FakePg pg) {
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(new ConcurrentHashMap<>());
        return new DialogCmpPersistence(hot, pg, 60_000);
    }

    @Test
    void savesAreQueuedAndCoalescedPerCallId() {
        FakePg pg = new FakePg();
        DialogCmpPersistence cmp = store(pg);
        cmp.save(snap("c1", DialogCheckpoint.STATE_INVITE));
        cmp.save(snap("c1", DialogCheckpoint.STATE_EARLY));
        cmp.save(snap("c1", DialogCheckpoint.STATE_CONFIRMED));

        assertEquals(0, pg.syncCalls, "no PG round trip on the SBB thread");
        assertEquals(1, cmp.pendingPgWrites());
        cmp.flushPg();
        assertEquals(1, pg.upserts.size());
        assertEquals(DialogCheckpoint.STATE_CONFIRMED, pg.upserts.getFirst().effectiveState());
    }

    @Test
    void endedDialogIsTombstonedSoRetransmitsSkipPg() {
        FakePg pg = new FakePg();
        DialogCmpPersistence cmp = store(pg);
        cmp.save(snap("c2", DialogCheckpoint.STATE_CONFIRMED));
        cmp.remove("c2");

        assertTrue(cmp.load("c2").isEmpty());
        cmp.flushPg();
        assertTrue(cmp.load("c2").isEmpty(), "BYE retransmit after drain");
        assertEquals(0, pg.loads, "tombstone answers before PG");
        assertEquals(List.of("c2"), pg.ended);
    }

    @Test
    void queuedCheckpointIsVisibleToPgFallbackLoad() {
        FakePg pg = new FakePg();
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(new ConcurrentHashMap<>());
        DialogCmpPersistence nodeA = new DialogCmpPersistence(hot, pg, 60_000);
        nodeA.save(snap("c3", DialogCheckpoint.STATE_CONFIRMED));
        hot.remove("c3"); // ISPN miss (e.g. evicted) — the queued PG op must still answer

        assertEquals(DialogCheckpoint.STATE_CONFIRMED, nodeA.load("c3").orElseThrow().dialogState());
        assertEquals(0, pg.loads);
    }

    @Test
    void loadHotNeverTouchesPg() {
        FakePg pg = new FakePg();
        DialogCmpPersistence cmp = store(pg);

        assertTrue(cmp.loadHot("fresh-call").isEmpty());
        assertEquals(0, pg.loads);
    }
}
