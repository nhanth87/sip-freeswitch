package et.elisa.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.cluster.CallSession;
import com.microjainslee.ra.freeswitch.events.FsChannelEvent;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CallSessionSbbTest {

    @Test
    void createExtractsSipAndCaller() {
        AtomicReference<CallSession> captured = new AtomicReference<>();
        CallSessionService stub = new CallSessionService() {
            @Override
            public CallSession upsertFromFs(String fsUuid, String sipCallId, String caller, String callee,
                                            String direction, String state, String fsNodeId) {
                CallSession s = new CallSession(fsUuid, sipCallId, null, caller, null,
                        "elisa-1", fsNodeId, direction, state, java.time.Instant.now(), java.time.Instant.now());
                captured.set(s);
                return s;
            }
        };
        CallSessionSbb sbb = new CallSessionSbb.$Concrete(stub, "fs-1");
        sbb.onChannel(new FsChannelEvent("CHANNEL_CREATE", "uuid-1", Map.of(
                "variable_sip_call_id", "call-abc",
                "Caller-Caller-ID-Number", "251911",
                "Caller-Destination-Number", "1000",
                "Call-Direction", "inbound")));
        assertEquals("uuid-1", captured.get().fsUuid());
        assertEquals("call-abc", captured.get().sipCallId());
        assertEquals("CREATED", captured.get().state());
    }

    @Test
    void hangupEndsSession() {
        AtomicReference<String> ended = new AtomicReference<>();
        CallSessionService stub = new CallSessionService() {
            @Override
            public void endByFsUuid(String fsUuid, String hangupState) {
                ended.set(fsUuid);
            }
        };
        CallSessionSbb sbb = new CallSessionSbb.$Concrete(stub, "fs-1");
        sbb.onChannel(new FsChannelEvent("CHANNEL_HANGUP", "uuid-9", Map.of()));
        assertEquals("uuid-9", ended.get());
    }

    @Test
    void nullService_noThrow() {
        CallSessionSbb sbb = new CallSessionSbb.$Concrete(null, "fs-1");
        sbb.onChannel(new FsChannelEvent("CHANNEL_CREATE", "u", Map.of()));
        assertTrue(true);
    }
}
