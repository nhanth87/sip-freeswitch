package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SipCallIdsTest {

    @Test
    void canonicalDecodesSofiaPercent40() {
        assertEquals("ab10rtp-1@172.21.0.1", SipCallIds.canonical("ab10rtp-1%40172.21.0.1"));
        assertEquals("ab10rtp-1@172.21.0.1", SipCallIds.canonical("ab10rtp-1@172.21.0.1"));
    }

    @Test
    void canonicalLeavesNullAndBlank() {
        assertNull(SipCallIds.canonical(null));
        assertEquals("", SipCallIds.canonical(""));
        assertEquals("   ", SipCallIds.canonical("   "));
    }
}
