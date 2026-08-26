package et.elisa.ims.scscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.events.SipAckEvent;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import et.elisa.ims.support.ImsTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * W1-a — ACK / re-INVITE checkpoint cadence on a live CONFIRMED dialog:
 * state never downgrades; HA/cseq stay fresh for N–N failover.
 */
class ScscfInviteSbbCheckpointCadenceTest {

    private static final long NOW = Instant.now().toEpochMilli();

    private record Fixture(DialogCmpPersistence cmp, ScscfInviteSbb.$Concrete sbb) {
        static Fixture confirmed(String callId) {
            ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
            DialogCmpPersistence cmp = new DialogCmpPersistence(
                    new DialogCheckpointStore().shareMemory(shared)).withoutPg();
            cmp.save(new InviteCmpSnapshot(
                    callId, "fs-1", "251911",
                    "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:1000@127.0.0.1:5062",
                    "10.0.0.9:5060", "fs-1", "elisa-1",
                    DialogCheckpoint.STATE_CONFIRMED, NOW, NOW));
            ScscfInviteSbb.$Concrete sbb = new ScscfInviteSbb.$Concrete(
                    ImsTestSupport.roles(false, false, true, false),
                    null, null, cmp, null, null, "elisa-1", "fs-1");
            assertTrue(sbb.recoverAfterFailure(callId));
            return new Fixture(cmp, sbb);
        }
    }

    @Test
    void ackKeepsConfirmedStateAndRefreshesCheckpoint() {
        Fixture f = Fixture.confirmed("cid-ack");
        long before = f.sbb().getUpdatedAtEpochMs();

        f.sbb().onAck(new SipAckEvent("cid-ack"));

        assertEquals(DialogCheckpoint.STATE_CONFIRMED, f.sbb().getDialogState(),
                "ACK must not change dialog state");
        assertTrue(f.sbb().getUpdatedAtEpochMs() >= before);
        assertEquals(DialogCheckpoint.STATE_CONFIRMED,
                f.cmp().load("cid-ack").orElseThrow().dialogState());
    }

    @Test
    void ackForUnknownCallIdDoesNotTouchCheckpoint() {
        Fixture f = Fixture.confirmed("cid-ack2");
        long before = f.sbb().getUpdatedAtEpochMs();

        f.sbb().onAck(new SipAckEvent("cid-other"));

        assertEquals(before, f.sbb().getUpdatedAtEpochMs());
    }

    @Test
    void reInviteDoesNotDowngradeConfirmedDialog() {
        Fixture f = Fixture.confirmed("cid-reinv");
        long startedAt = f.sbb().getStartedAtEpochMs();
        long durationAnchor = startedAt;

        SipInviteEvent reInvite = new SipInviteEvent(
                "cid-reinv", "sip:alice@ims.lab", "sip:bob@ims.lab",
                "sip:alice@10.0.0.9:5060", List.of(), List.of(), List.of(),
                "v=0", "application/sdp", Map.of());
        f.sbb().onInvite(reInvite);

        assertEquals(DialogCheckpoint.STATE_CONFIRMED, f.sbb().getDialogState(),
                "re-INVITE must not downgrade CONFIRMED → INVITE (recovery policy)");
        assertEquals(durationAnchor, f.sbb().getStartedAtEpochMs(),
                "CDR duration anchor preserved");
        assertEquals("sip:1000@127.0.0.1:5062", f.sbb().getTrunkUri(),
                "re-INVITE forwards to stored trunk, no re-routing");
        assertEquals(DialogCheckpoint.STATE_CONFIRMED,
                f.cmp().load("cid-reinv").orElseThrow().dialogState());
    }

    @Test
    void freshInviteStillInitializesNewDialog() {
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(shared);
        DialogCmpPersistence cmp = new DialogCmpPersistence(hot).withoutPg();
        ScscfInviteSbb.$Concrete sbb = new ScscfInviteSbb.$Concrete(
                ImsTestSupport.roles(false, false, true, false),
                null, null, cmp, null, null, "elisa-1", "fs-1");

        sbb.onInvite(new SipInviteEvent(
                "cid-new", "sip:alice@ims.lab", "sip:+251900000001@ims.lab",
                "sip:alice@10.0.0.9:5060", List.of(), List.of(), List.of(),
                "v=0", "application/sdp", Map.of()));

        assertEquals("cid-new", sbb.getSipCallId());
        assertEquals(DialogCheckpoint.STATE_INVITE,
                cmp.load("cid-new").orElseThrow().dialogState());
        assertTrue(sbb.getTrunkUri() != null);
    }
}
