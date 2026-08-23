package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiameterSeamTest {

    @Test
    void defaultIsListenOnlyListenOn3868() {
        DiameterSeam s = DiameterSeam.DEFAULT;
        assertFalse(s.hasPeer());
        assertEquals("client", s.peerRole());
        assertEquals("epc.lab", s.originRealm());
        assertEquals(3868, s.peerPort());
    }

    @Test
    void peerHostEnablesCerInitiation() {
        DiameterSeam s = new DiameterSeam("mme", 3868, "", "hss.epc.lab", "epc.lab", "epc-hss-1.epc.lab", "epc.lab");
        assertTrue(s.hasPeer());
        assertEquals("mme", s.peerHost());
        assertEquals("hss.epc.lab", s.destinationHost());
        assertEquals("epc.lab", s.destinationRealm());
        assertEquals("client", s.peerRole()); // normalized from blank
    }

    @Test
    void nonePeerIsTreatedAsNoPeer() {
        DiameterSeam s = new DiameterSeam("none", 3868, "server", null, null, null, "epc.lab");
        assertFalse(s.hasPeer());
        assertEquals("server", s.peerRole());
    }
}