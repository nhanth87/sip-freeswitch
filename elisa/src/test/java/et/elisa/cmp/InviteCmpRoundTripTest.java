package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import et.elisa.cluster.CallSession;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.ims.scscf.ScscfInviteSbb;
import et.elisa.ims.support.ImsTestSupport;
import com.microjainslee.ra.freeswitch.events.FsChannelEvent;
import et.elisa.session.CallSessionSbb;
import et.elisa.session.CallSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class InviteCmpRoundTripTest {

    @Test
    void snapshotRoundTripsThroughMemoryStore() {
        DialogCmpPersistence store = new DialogCmpPersistence().withoutPg();
        long now = Instant.now().toEpochMilli();
        InviteCmpSnapshot snap = new InviteCmpSnapshot(
                "call-1", null, "251911", "sip:a@lab", "sip:b@lab", "sip:as@fs",
                null, "fs-1", "elisa-1", DialogCheckpoint.STATE_INVITE, now, now);
        store.save(snap);

        Optional<InviteCmpSnapshot> loaded = store.load("call-1");
        assertTrue(loaded.isPresent());
        assertEquals("call-1", loaded.get().sipCallId());
        assertEquals("251911", loaded.get().msisdn());
        assertEquals(DialogCheckpoint.STATE_INVITE, loaded.get().dialogState());
        assertEquals("sip:as@fs", loaded.get().trunkUri());
    }

    @Test
    void encodedSofiaCallIdLoadsCanonicalCmp() {
        DialogCmpPersistence store = new DialogCmpPersistence().withoutPg();
        long now = Instant.now().toEpochMilli();
        store.save(new InviteCmpSnapshot(
                "1-9@127.0.0.1", "fs-u", "alice", "sip:alice@ims.lab", "sip:alice@ims.lab",
                "sip:1000@freeswitch:5060", null, "fs-1", "elisa-1",
                DialogCheckpoint.STATE_CONFIRMED, now, now));
        assertTrue(store.load("1-9%40127.0.0.1").isPresent());
        assertEquals("fs-u", store.load("1-9%40127.0.0.1").orElseThrow().fsUuid());
    }

    @Test
    void sbbRecoverAfterFailureRestoresCmp() {
        DialogCmpPersistence store = new DialogCmpPersistence().withoutPg();
        ScscfInviteSbb sbb = new ScscfInviteSbb.$Concrete(
                ImsTestSupport.roles(false, false, true, false),
                null, null, store, null, null, "elisa-1", "fs-1");

        long now = Instant.now().toEpochMilli();
        store.save(new InviteCmpSnapshot(
                "cid-recover", "fs-uuid-9", "251900",
                "sip:from@lab", "sip:to@lab", "sip:trunk@fs",
                "10.0.0.1", "fs-1", "elisa-1",
                DialogCheckpoint.STATE_CONFIRMED, now, now));

        assertTrue(sbb.recoverAfterFailure("cid-recover"));
        assertEquals("cid-recover", sbb.getSipCallId());
        assertEquals("fs-uuid-9", sbb.getFsUuid());
        assertEquals(DialogCheckpoint.STATE_CONFIRMED, sbb.getDialogState());
        assertEquals("sip:trunk@fs", sbb.getTrunkUri());
        assertEquals("251900", sbb.getMsisdn());
    }

    @Test
    void inviteWriteThroughThenSimulateDeath() {
        DialogCmpPersistence store = new DialogCmpPersistence().withoutPg();
        var roles = ImsTestSupport.roles(false, false, true, false);
        ScscfInviteSbb alive = new ScscfInviteSbb.$Concrete(
                roles, null, null, store, null, null, "n1", "fs-a");
        alive.onEvent(new SipInviteEvent(
                "cid-live", "sip:alice@lab", "sip:bob@lab",
                null, List.of(), List.of(), List.of(), null, null), null);

        assertEquals("cid-live", alive.getSipCallId());
        assertEquals(DialogCheckpoint.STATE_INVITE, alive.getDialogState());
        assertTrue(store.load("cid-live").isPresent());

        ScscfInviteSbb resurrected = new ScscfInviteSbb.$Concrete(
                roles, null, null, store, null, null, "n1", "fs-a");
        assertTrue(resurrected.recoverAfterFailure("cid-live"));
        assertEquals("sip:alice@lab", resurrected.getFromUri());
        assertEquals("sip:bob@lab", resurrected.getToUri());
    }

    @Test
    void callSessionSbbRecoverAfterFailure() {
        ConcurrentHashMap<String, CallSession> map = new ConcurrentHashMap<>();
        CallSessionService stub = new CallSessionService() {
            @Override
            public CallSession upsertFromFs(String fsUuid, String sipCallId, String caller, String callee,
                                            String direction, String state, String fsNodeId) {
                CallSession s = new CallSession(fsUuid, sipCallId, null, caller, null,
                        "elisa-1", fsNodeId, direction, state, Instant.now(), Instant.now());
                map.put(fsUuid, s);
                return s;
            }

            @Override
            public Optional<CallSession> findByFsUuid(String fsUuid) {
                return Optional.ofNullable(map.get(fsUuid));
            }
        };
        CallSessionSbb sbb = new CallSessionSbb.$Concrete(stub, "fs-1");
        sbb.onEvent(new FsChannelEvent("CHANNEL_CREATE", "uuid-cmp", Map.of(
                "variable_sip_call_id", "sip-cmp",
                "Caller-Caller-ID-Number", "251911",
                "Caller-Destination-Number", "1000",
                "Call-Direction", "inbound")), null);
        assertEquals("uuid-cmp", sbb.getFsUuid());
        assertEquals("sip-cmp", sbb.getSipCallId());

        CallSessionSbb other = new CallSessionSbb.$Concrete(stub, "fs-1");
        assertTrue(other.recoverAfterFailure("uuid-cmp"));
        assertEquals("sip-cmp", other.getSipCallId());
        assertEquals("CREATED", other.getDialogState());
        assertFalse(other.recoverAfterFailure("missing"));
    }

    @Test
    void checkpointCompatCtorsStillWork() {
        DialogCheckpoint cp = new DialogCheckpoint(
                "c", "peer", "from", "to", "trunk", "fs", "elisa", Instant.now());
        assertEquals(DialogCheckpoint.STATE_INVITE, cp.effectiveState());
        DialogCheckpoint answered = cp.withState(DialogCheckpoint.STATE_CONFIRMED).withFs("u1", "251");
        assertEquals("u1", answered.fsUuid());
        assertEquals(DialogCheckpoint.STATE_CONFIRMED, answered.effectiveState());
    }
}
