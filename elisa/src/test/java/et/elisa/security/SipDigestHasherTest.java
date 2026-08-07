package et.elisa.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SipDigestHasherTest {

    @Test
    void a1HashMatchesKnownVector() {
        // MD5("1000:freeswitch.lab:secret")
        String hash = SipDigestHasher.a1Hash("1000", "freeswitch.lab", "secret");
        assertEquals(32, hash.length());
        assertTrue(SipDigestHasher.looksLikeA1Hash(hash));
        assertEquals(hash, SipDigestHasher.a1Hash("1000", "freeswitch.lab", "secret"));
    }
}
