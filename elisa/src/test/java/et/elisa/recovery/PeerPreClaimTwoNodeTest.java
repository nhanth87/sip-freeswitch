package et.elisa.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;
import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * W1-b — two-node DIST_SYNC sim of a mid-call home death: the surviving peer
 * claims ownership and spawns the dialog entity BEFORE any inbound arrives,
 * with Send* wire rebuilt from the rich checkpoint (blockedSipHa == 0).
 * TERMINATED / checkpoint-less correlates are never claimed (R6).
 */
class PeerPreClaimTwoNodeTest {

    private static final long NOW = Instant.now().toEpochMilli();
    private static final String HOME = "elisa-scscf-1";
    private static final String PEER = "elisa-scscf-2";

    @Test
    void peerClaimsAndRebuildsWireBeforeInboundArrives() {
        // Shared planes = DIST_SYNC sim: checkpoints + ownership fence.
        ConcurrentHashMap<String, DialogCheckpoint> sharedCp = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, DialogOwner> sharedOwners = new ConcurrentHashMap<>();

        DialogCheckpointStore homeHot = new DialogCheckpointStore().shareMemory(sharedCp);
        DialogOwnerStore homeOwners = new DialogOwnerStore().shareMemory(HOME, sharedOwners);
        homeSave(homeHot, homeOwners, "cid-nn");

        assertEquals(HOME, sharedOwners.get("cid-nn").ownerNodeId());
        assertEquals(0L, sharedOwners.get("cid-nn").generation());

        // ── home dies; peer sees the view change (no inbound yet) ──
        DialogCheckpointStore peerHot = new DialogCheckpointStore().shareMemory(sharedCp);
        DialogOwnerStore peerOwners = new DialogOwnerStore().shareMemory(PEER, sharedOwners);
        SbbRecoveryManagerImpl peerMgr = new SbbRecoveryManagerImpl(
                null, new DialogCmpPersistence(peerHot, peerOwners).withoutPg(),
                null, peerOwners, peerHot, null, null);
        SipServletResourceAdaptor peerRa = new SipServletResourceAdaptor();
        peerMgr.bindSipRa(peerRa);

        peerMgr.onClusterViewChange(new ClusterView(
                PEER, Set.of(PEER), List.of(HOME), true));

        // Pre-inbound: fence moved to peer, entity restored, wire rebuilt.
        DialogOwner claimed = sharedOwners.get("cid-nn");
        assertNotNull(claimed);
        assertEquals(PEER, claimed.ownerNodeId(), "peer must own the orphan before BYE");
        assertEquals(1L, claimed.generation());
        assertEquals(1L, peerMgr.failoverClaimWon());
        assertEquals(0L, peerMgr.failoverClaimLost());
        assertEquals(0L, peerMgr.blockedSipHa(),
                "rich CONFIRMED checkpoint → Send* wire without inbound");
        assertTrue(peerRa.hasDialog("cid-nn"));
        assertNotNull(peerRa.dialogRegistry().find("cid-nn").lastRequest());

        // Dual spawn loses the fence.
        assertFalse(peerMgr.failoverSpawn(
                RecoveryKey.sipCallId("cid-nn", HOME), 0L));
        assertEquals(1L, peerMgr.failoverClaimLost());
        assertEquals(PEER, sharedOwners.get("cid-nn").ownerNodeId());
        assertEquals(DialogCheckpoint.STATE_CONFIRMED,
                peerHot.get("cid-nn").orElseThrow().effectiveState());
    }

    @Test
    void terminatedOrphanIsNeverClaimed() {
        ConcurrentHashMap<String, DialogCheckpoint> sharedCp = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, DialogOwner> sharedOwners = new ConcurrentHashMap<>();

        // Owner row survives a crashed BYE cleanup — but no live checkpoint.
        sharedOwners.put("cid-bye",
                new DialogOwner("cid-bye", HOME, 0L, NOW));

        DialogCheckpointStore peerHot = new DialogCheckpointStore().shareMemory(sharedCp);
        DialogOwnerStore peerOwners = new DialogOwnerStore().shareMemory(PEER, sharedOwners);
        SbbRecoveryManagerImpl peerMgr = new SbbRecoveryManagerImpl(
                null, new DialogCmpPersistence(peerHot, peerOwners).withoutPg(),
                null, peerOwners, peerHot, null, null);

        peerMgr.onClusterViewChange(new ClusterView(
                PEER, Set.of(PEER), List.of(HOME), true));

        assertEquals(0L, peerMgr.failoverClaimWon(), "R6: no checkpoint → NOOP");
        assertEquals(HOME, sharedOwners.get("cid-bye").ownerNodeId(),
                "stale owner row untouched");
    }

    /**
     * Home-side save helper — rich HA export mirrors what {@code DialogCmpPersistence}
     * stores when the RA enricher is bound (W1-a monotonic HA keeps it across saves).
     */
    private static void homeSave(DialogCheckpointStore hot, DialogOwnerStore owners,
                                 String callId) {
        DialogCmpPersistence cmp = new DialogCmpPersistence(hot, owners).withoutPg();
        DialogRegistry live = new DialogRegistry();
        live.restorePortable(new DialogRegistry.PortableDialogMeta(
                callId, "10.0.0.9", 5060, "UDP",
                "10.0.0.2", 5060, "UDP", 7L,
                "sip:alice@ims.lab", "ua-from", "sip:bob@ims.lab", "scscf-to",
                "sip:alice@10.0.0.9:5060", "sip:1000@10.0.0.2:5060",
                "INVITE", "sip:alice@ims.lab", "far-uac"), () -> callId);
        cmp.setSipHaEnricher(live::exportPortable);
        cmp.save(new InviteCmpSnapshot(
                callId, "fs-1", "251911",
                "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:1000@127.0.0.1:5062",
                null, "fs-1", HOME, DialogCheckpoint.STATE_CONFIRMED, NOW, NOW));
    }
}
