package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;
import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.SipDialogHa;
import et.elisa.recovery.DialogOwnerStore;
import et.elisa.recovery.RecoveryKey;
import et.elisa.recovery.SbbRecoveryManagerImpl;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * W1-a — monotonic SipDialogHa checkpoints: every save past INVITE leaves a
 * richer-or-equal HA block, so N–N failover never hits blockedSipHa for a
 * dialog that reached EARLY/CONFIRMED before node death.
 */
class SipHaMonotonicCheckpointTest {

    private static final long NOW = Instant.now().toEpochMilli();

    private static InviteCmpSnapshot snap(String callId, String state, SipDialogHa ha) {
        return new InviteCmpSnapshot(
                callId, "fs-1", "251911",
                "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:1000@127.0.0.1:5062",
                "10.0.0.9:5060", "fs-1", "elisa-1", state, NOW, NOW,
                ha != null ? ha : SipDialogHa.EMPTY);
    }

    @Test
    void laterSaveWithEmptyRaExportNeverDegradesCheckpointedTags() {
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(shared);
        DialogCmpPersistence cmp = new DialogCmpPersistence(hot).withoutPg();

        DialogRegistry live = new DialogRegistry();
        live.restorePortable(wireMeta("cid-mono"), () -> "cid-mono");
        cmp.setSipHaEnricher(live::exportPortable);
        cmp.save(snap("cid-mono", DialogCheckpoint.STATE_CONFIRMED, null));
        assertEquals("ua-from", cmp.load("cid-mono").orElseThrow()
                .effectiveSipHa().fromTag());

        // Entity recreated / registry lost — enricher now exports nothing.
        cmp.setSipHaEnricher(callId -> null);
        cmp.save(snap("cid-mono", DialogCheckpoint.STATE_CONFIRMED, null));

        SipDialogHa ha = cmp.load("cid-mono").orElseThrow().effectiveSipHa();
        assertFalse(ha.isEmpty(), "monotonic merge must keep earlier wire essentials");
        assertEquals("ua-from", ha.fromTag());
        assertEquals("scscf-to", ha.toTag());
        assertEquals(7L, ha.cseq());
    }

    @Test
    void newerPartialExportMergesOverOlderFull() {
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(shared);
        DialogCmpPersistence cmp = new DialogCmpPersistence(hot).withoutPg();

        cmp.save(snap("cid-part", DialogCheckpoint.STATE_EARLY,
                new SipDialogHa("10.0.0.2:5060", "UDP", "UDP", 3L,
                        "ft-old", "tt-old", "sip:a@x", "sip:b@y", "INVITE",
                        "sip:a@x", "far-old")));

        // Later export carries fresh cseq + to-tag only (e.g. early 18x from far end).
        cmp.save(snap("cid-part", DialogCheckpoint.STATE_EARLY,
                new SipDialogHa(null, null, null, 5L, null, "tt-new",
                        null, null, null, null, null)));

        SipDialogHa ha = cmp.load("cid-part").orElseThrow().effectiveSipHa();
        assertEquals(5L, ha.cseq(), "newer non-zero cseq wins");
        assertEquals("tt-new", ha.toTag());
        assertEquals("ft-old", ha.fromTag(), "older tag survives partial overwrite");
        assertEquals("far-old", ha.farFromTag());
    }

    @Test
    void failoverSpawnWithRichCheckpointHasZeroBlockedSipHa() {
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(shared);
        DialogOwnerStore owners = new DialogOwnerStore().memoryOnly("elisa-1");
        DialogCmpPersistence cmp = new DialogCmpPersistence(hot, owners).withoutPg();

        DialogRegistry homeLive = new DialogRegistry();
        homeLive.restorePortable(wireMeta("cid-rich"), () -> "cid-rich");
        cmp.setSipHaEnricher(homeLive::exportPortable);
        cmp.save(new InviteCmpSnapshot(
                "cid-rich", null, "251911",
                "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:1000@127.0.0.1:5062",
                null, "fs-1", "dead-node", DialogCheckpoint.STATE_CONFIRMED, NOW, NOW));
        assertFalse(cmp.load("cid-rich").orElseThrow().effectiveSipHa().isEmpty());

        SbbRecoveryManagerImpl mgr = new SbbRecoveryManagerImpl(
                null, cmp, null, owners, hot, null, null);
        mgr.bindSipRa(new SipServletResourceAdaptor());

        assertTrue(mgr.failoverSpawn(RecoveryKey.sipCallId("cid-rich", "dead-node"), 0L));
        assertEquals("elisa-1", owners.get("cid-rich").orElseThrow().ownerNodeId());
        assertEquals(0L, mgr.blockedSipHa(),
                "rich CONFIRMED checkpoint must rebuild Send* wire without inbound");
    }

    private static DialogRegistry.PortableDialogMeta wireMeta(String callId) {
        return new DialogRegistry.PortableDialogMeta(
                callId, "10.0.0.9", 5060, "UDP",
                "10.0.0.2", 5060, "UDP", 7L,
                "sip:alice@ims.lab", "ua-from", "sip:bob@ims.lab", "scscf-to",
                "sip:alice@10.0.0.9:5060", "sip:1000@10.0.0.2:5060",
                "INVITE", "sip:alice@ims.lab", "far-uac");
    }
}
