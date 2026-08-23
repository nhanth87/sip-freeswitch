package et.epc.api.link;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PeerLinkPropertiesTest {

    @Test
    void upSetsListenAndLiveWithEvidence() {
        PeerLink p = PeerLink.up("pfcp", "assoc-response");
        assertTrue(p.localListen());
        assertTrue(p.live());
        assertEquals("assoc-response", p.evidence());
    }

    @Test
    void downNeverLive() {
        PeerLink p = PeerLink.down("gtp", false, "no-echo");
        assertFalse(p.localListen());
        assertFalse(p.live());
        assertEquals("no-echo", p.evidence());
    }

    @Test
    void liveRequiresNonBlankEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new PeerLink("x", true, true, ""));
        assertThrows(IllegalArgumentException.class, () -> new PeerLink("x", true, true, "   "));
    }

    @Test
    void nullNameRejected() {
        assertThrows(NullPointerException.class, () -> new PeerLink(null, true, false, "why"));
    }

    @Test
    void nullEvidenceRejected() {
        assertThrows(NullPointerException.class, () -> new PeerLink("x", true, false, null));
    }
}