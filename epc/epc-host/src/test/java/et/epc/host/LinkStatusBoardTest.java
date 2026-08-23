package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.link.PeerLink;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LinkStatusBoardTest {

    @Test
    void snapshotWithOnlyS6a() {
        S6aPeerWatch s6a = new S6aPeerWatch();
        s6a.ceaAccepted("hss.epc.mno");
        LinkStatusBoard board = new LinkStatusBoard(s6a, null, null, null, null);
        Map<String, PeerLink> m = board.snapshot();
        assertEquals(1, m.size());
        assertTrue(m.get("s6a").live());
        assertTrue(board.anyLive());
    }

    @Test
    void snapshotEmptyWhenAllNull() {
        LinkStatusBoard board = new LinkStatusBoard(null, null, null, null, null);
        assertTrue(board.snapshot().isEmpty());
        assertFalse(board.anyLive());
    }

    @Test
    void snapshotIsUnmodifiable() {
        LinkStatusBoard board = new LinkStatusBoard(null, null, null, null, null);
        Map<String, PeerLink> m = board.snapshot();
        try {
            m.put("x", PeerLink.down("x", false, "nope"));
            assertFalse(true, "expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }
}