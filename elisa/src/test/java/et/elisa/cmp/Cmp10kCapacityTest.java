package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.cluster.DialogCheckpoint;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 10k in-flight CMP snapshots on one JVM (canonical Call-ID, hangup evicts). */
class Cmp10kCapacityTest {

    private static final int N = 10_000;

    @Test
    @Timeout(30)
    void tenThousandDialogCmpCanonicalCallIdAndEvictOnTerminate() {
        DialogCmpPersistence store = new DialogCmpPersistence().withoutPg();
        long now = Instant.now().toEpochMilli();
        for (int i = 0; i < N; i++) {
            String wire = "c-" + i + "@127.0.0.1";
            store.save(new InviteCmpSnapshot(
                    wire, "fs-" + i, "u" + i,
                    "sip:u" + i + "@ims.lab", "sip:alice@ims.lab", "sip:1000@freeswitch:5060",
                    null, "fs-1", "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now, now));
        }

        assertTrue(store.load("c-0@127.0.0.1").isPresent());
        assertTrue(store.load("c-5000%40127.0.0.1").isPresent(), "FS %40 Call-ID must hit CMP");
        assertEquals("c-5000@127.0.0.1", store.load("c-5000%40127.0.0.1").orElseThrow().sipCallId());
        assertEquals("fs-9999", store.load("c-9999@127.0.0.1").orElseThrow().fsUuid());

        for (int i = 0; i < N; i++) {
            store.save(new InviteCmpSnapshot(
                    "c-" + i + "%40127.0.0.1", "fs-" + i, "u" + i,
                    "sip:u" + i + "@ims.lab", "sip:alice@ims.lab", "sip:1000@freeswitch:5060",
                    null, "fs-1", "elisa-1", DialogCheckpoint.STATE_TERMINATED, now, now));
        }
        assertFalse(store.load("c-0@127.0.0.1").isPresent());
        assertFalse(store.load("c-9999%40127.0.0.1").isPresent());
    }

    @Test
    @Timeout(10)
    void pgSyncOffStillServesMemoryHotPath() {
        DialogCmpPersistence store = new DialogCmpPersistence().withoutPgSync();
        long now = Instant.now().toEpochMilli();
        store.save(new InviteCmpSnapshot(
                "c-hot@127.0.0.1", "fs-1", "u1",
                "sip:u1@ims.lab", "sip:alice@ims.lab", "sip:1000@freeswitch:5060",
                null, "fs-1", "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now, now));
        assertTrue(store.load("c-hot@127.0.0.1").isPresent());
        assertEquals("fs-1", store.load("c-hot@127.0.0.1").orElseThrow().fsUuid());
    }
}
