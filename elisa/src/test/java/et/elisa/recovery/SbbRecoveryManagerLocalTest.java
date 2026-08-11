package et.elisa.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Local respawn decision + CMP restore round-trip without Quarkus container.
 */
class SbbRecoveryManagerLocalTest {

    private DialogOwnerStore owners;
    private DialogCmpPersistence cmp;
    private SbbRecoveryManagerImpl mgr;

    @BeforeEach
    void setUp() {
        owners = new DialogOwnerStore().memoryOnly("elisa-1");
        cmp = new DialogCmpPersistence(null, owners).withoutPg();
        mgr = new SbbRecoveryManagerImpl(null, cmp, null, owners, null, null, null);
    }

    @Test
    void localRespawnRestoresCmpWhenLive() {
        long now = System.currentTimeMillis();
        InviteCmpSnapshot snap = new InviteCmpSnapshot(
                "cid-live", "fs-1", "2519", "sip:a@x", "sip:b@x", "sip:trunk",
                "10.0.0.9:5060", "fs-a", "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now, now);
        cmp.save(snap);
        assertTrue(owners.get("cid-live").isPresent());

        assertTrue(mgr.respawnLocal(RecoveryKey.sipCallId("cid-live", "elisa-1"),
                RecoveryCause.EXCEPTION_ROLLBACK));
        assertEquals(1L, mgr.localOk());
        // Round-trip still loadable
        assertEquals(DialogCheckpoint.STATE_CONFIRMED,
                cmp.load("cid-live").orElseThrow().dialogState());
    }

    @Test
    void terminatedNoop() {
        long now = System.currentTimeMillis();
        cmp.save(new InviteCmpSnapshot(
                "cid-bye", null, null, null, null, null, null, null, "elisa-1",
                DialogCheckpoint.STATE_TERMINATED, now, now));
        assertFalse(mgr.respawnLocal(RecoveryKey.sipCallId("cid-bye"), RecoveryCause.EXCEPTION_ROLLBACK));
        assertEquals(1L, mgr.localFail());
    }

    @Test
    void missingCheckpointNoop() {
        assertFalse(mgr.respawnLocal(RecoveryKey.sipCallId("missing"), RecoveryCause.MISSING_ENTITY));
    }

    @Test
    void failoverClaimWinsThenRestores() {
        long now = System.currentTimeMillis();
        InviteCmpSnapshot snap = new InviteCmpSnapshot(
                "cid-fo", "fs-9", "2519", "sip:a@x", "sip:b@x", null,
                "10.0.0.1:5060", "fs-a", "dead-node", DialogCheckpoint.STATE_CONFIRMED, now, now);
        cmp.save(snap);
        assertEquals("dead-node", owners.get("cid-fo").orElseThrow().ownerNodeId());

        assertTrue(mgr.failoverSpawn(RecoveryKey.sipCallId("cid-fo", "dead-node"), 0L));
        assertEquals(1L, mgr.failoverClaimWon());
        assertEquals("elisa-1", owners.get("cid-fo").orElseThrow().ownerNodeId());
        assertEquals(1L, owners.get("cid-fo").orElseThrow().generation());
        assertFalse(mgr.failoverSpawn(RecoveryKey.sipCallId("cid-fo", "dead-node"), 0L));
        assertEquals(1L, mgr.failoverClaimLost());
    }

    @Test
    void selfRemoveAfterByeDoesNotRespawn() {
        long now = System.currentTimeMillis();
        cmp.save(new InviteCmpSnapshot(
                "cid-bye-rm", null, null, "sip:a@x", "sip:b@x", null, "10.0.0.1:5060",
                null, "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now, now));
        cmp.save(new InviteCmpSnapshot(
                "cid-bye-rm", null, null, "sip:a@x", "sip:b@x", null, "10.0.0.1:5060",
                null, "elisa-1", DialogCheckpoint.STATE_TERMINATED, now, now));
        var ev = new com.microjainslee.core.removal.EntityRemovalEvent(
                "ScscfInviteSbb/cid-bye-rm", "cid-bye-rm",
                com.microjainslee.core.removal.EntityRemovalEvent.RemovalReason.SBB_SELF_REMOVE,
                now);
        mgr.handleRemoval(ev);
        assertEquals(0L, mgr.localOk());
    }

    @Test
    void selfRemoveMidConfirmedRespawns() {
        long now = System.currentTimeMillis();
        cmp.save(new InviteCmpSnapshot(
                "cid-live-rm", "fs-1", "2519", "sip:a@x", "sip:b@x", null, "10.0.0.1:5060",
                "fs-a", "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now, now));
        var ev = new com.microjainslee.core.removal.EntityRemovalEvent(
                "ScscfInviteSbb/cid-live-rm", "cid-live-rm",
                com.microjainslee.core.removal.EntityRemovalEvent.RemovalReason.SBB_SELF_REMOVE,
                now);
        mgr.handleRemoval(ev);
        assertEquals(1L, mgr.localOk());
    }

    @Test
    void viewChangeClaimsOrphansOfDepartedHome() {
        long now = System.currentTimeMillis();
        InviteCmpSnapshot snap = new InviteCmpSnapshot(
                "cid-view", "fs-9", "2519", "sip:a@x", "sip:b@x", null,
                "10.0.0.1:5060", "fs-a", "elisa-scscf-1", DialogCheckpoint.STATE_CONFIRMED, now, now);
        cmp.save(snap);
        assertEquals("elisa-scscf-1", owners.get("cid-view").orElseThrow().ownerNodeId());

        ClusterView view = new ClusterView(
                "elisa-1",
                java.util.Set.of("elisa-1"),
                java.util.List.of("elisa-scscf-1"),
                true);
        mgr.onClusterViewChange(view);
        assertEquals(1L, mgr.failoverClaimWon());
        assertEquals("elisa-1", owners.get("cid-view").orElseThrow().ownerNodeId());
    }

    @Test
    void decideLocalWhenHomeIsUs() {
        long now = System.currentTimeMillis();
        cmp.save(new InviteCmpSnapshot(
                "cid-home", null, null, null, null, null, null, null, "elisa-1",
                DialogCheckpoint.STATE_EARLY, now, now));
        RecoveryDecision d = mgr.decide(RecoveryKey.sipCallId("cid-home", "elisa-1"), false);
        assertEquals(RecoveryDecision.LOCAL_RESPAWN, d);
    }
}
