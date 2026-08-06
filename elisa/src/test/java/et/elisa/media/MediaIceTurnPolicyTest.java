package et.elisa.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MediaIceTurnPolicyTest {

    @Test
    void rejectsMissingIceAndTurn() {
        assertNull(policy(false, false, false).rejectReason("v=0"));
        assertEquals("ICE required", policy(true, false, false).rejectReason("v=0\nm=audio 9 RTP/AVP 0\n"));
        assertEquals("TURN relay candidate required",
                policy(true, true, false).rejectReason("v=0\na=ice-ufrag:x\na=candidate:1 1 UDP 1 1.2.3.4 9 typ host\n"));
        assertNull(policy(true, true, false).rejectReason(
                "v=0\na=ice-ufrag:x\na=candidate:1 1 UDP 1 203.0.113.9 3478 typ relay raddr 10.0.0.1\n"));
    }

    @Test
    void rtpRedirectForcesTurnRelayThroughFirewall() {
        MediaIceTurnPolicy p = policy(false, false, true);
        assertTrue(p.rtpRedirect());
        assertTrue(p.requireTurn());
        assertEquals("ICE required", p.rejectReason("v=0\nm=audio 9 RTP/AVP 0\n"));
        assertEquals("RTP redirect (TURN relay) required",
                p.rejectReason("v=0\na=ice-ufrag:x\na=candidate:1 1 UDP 1 10.0.0.2 9 typ host\n"));
        assertNull(p.rejectReason(
                "v=0\na=ice-ufrag:x\na=candidate:1 1 UDP 1 203.0.113.9 3478 typ relay\n"));
    }

    private static MediaIceTurnPolicy policy(boolean ice, boolean turn, boolean redirect) {
        MediaIceTurnPolicy p = new MediaIceTurnPolicy();
        try {
            setBool(p, "iceRequired", ice);
            setBool(p, "requireTurn", turn);
            setBool(p, "rtpRedirect", redirect);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return p;
    }

    private static void setBool(MediaIceTurnPolicy p, String field, boolean value)
            throws ReflectiveOperationException {
        var f = MediaIceTurnPolicy.class.getDeclaredField(field);
        f.setAccessible(true);
        f.setBoolean(p, value);
    }
}
