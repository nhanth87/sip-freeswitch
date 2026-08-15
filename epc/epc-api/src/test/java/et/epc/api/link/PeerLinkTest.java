package et.epc.api.link;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PeerLinkTest {

    @Test
    void listenAloneIsNotLive() {
        PeerLink down = PeerLink.down("s6a", true, "no-cea");
        assertTrue(down.localListen());
        assertFalse(down.live());
    }

    @Test
    void liveRequiresEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new PeerLink("gtp", true, true, ""));
        assertTrue(PeerLink.up("gtp", "echo-response").live());
    }
}
