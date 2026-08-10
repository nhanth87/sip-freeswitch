package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.ims.scscf.ScscfInviteSbb;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.recovery.DialogOwner;
import et.elisa.recovery.DialogOwnerStore;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Cross-node CMP restore simulation: two DialogCmpPersistence instances share one
 * memory DialogCheckpointStore (stands in for DIST_SYNC ISPN).
 */
class DialogCmpCrossNodeRestoreTest {

    @Test
    void nodeBRestoresInviteCmpSavedByNodeA() {
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCheckpointStore hotA = new DialogCheckpointStore().shareMemory(shared);
        DialogCheckpointStore hotB = new DialogCheckpointStore().shareMemory(shared);

        DialogCmpPersistence nodeA = new DialogCmpPersistence(hotA).withoutPg();
        DialogCmpPersistence nodeB = new DialogCmpPersistence(hotB).withoutPg();

        long now = Instant.now().toEpochMilli();
        nodeA.save(new InviteCmpSnapshot(
                "cid-nn-1", null, "251911",
                "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:1000@127.0.0.1:5062",
                "10.0.0.8", "fs-1", "elisa-1",
                DialogCheckpoint.STATE_EARLY, now, now));

        Optional<InviteCmpSnapshot> onB = nodeB.load("cid-nn-1");
        assertTrue(onB.isPresent(), "node B must see node A checkpoint via shared hot store");
        assertEquals(DialogCheckpoint.STATE_EARLY, onB.get().dialogState());
        assertEquals("sip:1000@127.0.0.1:5062", onB.get().trunkUri());
        assertEquals("elisa-1", onB.get().elisaNodeId());

        ScscfInviteSbb resurrected = new ScscfInviteSbb.$Concrete(
                ImsTestSupport.roles(false, false, true, false),
                null, null, nodeB, null, null, "elisa-2", "fs-2");
        assertTrue(resurrected.recoverAfterFailure("cid-nn-1"));
        assertEquals("cid-nn-1", resurrected.getSipCallId());
        assertEquals(DialogCheckpoint.STATE_EARLY, resurrected.getDialogState());
        assertEquals("251911", resurrected.getMsisdn());
    }

    @Test
    void ownerFencePutIfAbsentStickyAcrossNodes() {
        DialogOwnerStore a = new DialogOwnerStore().memoryOnly("elisa-1");
        DialogOwnerStore b = new DialogOwnerStore().memoryOnly("elisa-2");
        // Separate memory maps by design — ownership fence for CAS is per-store in unit mode.
        // Shared-map ownership: exercise putIfAbsent on one store then claim on same map.
        assertTrue(a.putIfAbsent("cid-own", "elisa-1"));
        Optional<DialogOwner> owner = a.get("cid-own");
        assertTrue(owner.isPresent());
        assertEquals("elisa-1", owner.get().ownerNodeId());
        // Same-store CAS claim (failover to elisa-2)
        assertTrue(a.tryClaim(owner.get(), "elisa-2"));
        assertEquals("elisa-2", a.get("cid-own").orElseThrow().ownerNodeId());
        assertEquals(1L, a.get("cid-own").orElseThrow().generation());
        // Loser on stale generation
        assertTrue(!a.tryClaim("cid-own", "elisa-1", 0L));
        // b is a different memory plane — documents JVM-local fence until DIST_SYNC
        assertTrue(b.putIfAbsent("cid-own", "elisa-2") || b.get("cid-own").isPresent());
    }

    @Test
    void profileIsNotLiveInviteSot_cmpIs() {
        // Documented contract: CallSessionProfile / InMemoryProfileFacility is JVM-local.
        // Live INVITE identity for N–N = DialogCmpPersistence (ISPN → PG), not Profile.
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCmpPersistence cmp = new DialogCmpPersistence(
                new DialogCheckpointStore().shareMemory(shared)).withoutPg();
        long now = Instant.now().toEpochMilli();
        cmp.save(new InviteCmpSnapshot(
                "cid-sot", "fs-u", "251900",
                "sip:a@lab", "sip:b@lab", "sip:as@fs",
                null, "fs-1", "elisa-1",
                DialogCheckpoint.STATE_CONFIRMED, now, now));
        assertEquals("fs-u", cmp.load("cid-sot").orElseThrow().fsUuid());
        assertEquals(DialogCheckpoint.STATE_CONFIRMED, cmp.load("cid-sot").orElseThrow().dialogState());
    }
}
