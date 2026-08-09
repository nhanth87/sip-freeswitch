package et.elisa.ims.aka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Aggressive AKA edge cases — expect failures that reveal crypto/auth bugs. */
class AkaMd5AggressiveTest {

    private static final byte[] KI = AkaMd5.parseHex("00112233445566778899aabbccddeeff");
    private static final byte[] OPC = AkaMd5.parseHex("000102030405060708090a0b0c0d0e0f");
    private static final byte[] AMF = AkaMd5.parseHex("8000");

    @Test
    void shortResMustNotVerifyAgainstFullXres() {
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, 7);
        byte[] prefix = Arrays.copyOf(v.xres(), 1);
        // Prefix match must NOT authenticate (truncated attack)
        assertFalse(AkaMd5.verifyRes(v.xres(), prefix),
                "BUG: truncated RES accepted — verifyRes uses min-length compare");
    }

    @Test
    void emptyResMustFail() {
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, 1);
        assertFalse(AkaMd5.verifyRes(v.xres(), new byte[0]));
        assertFalse(AkaMd5.verifyRes(v.xres(), null));
        assertFalse(AkaMd5.verifyRes(null, v.xres()));
    }

    @Test
    void longerWrongResMustFail() {
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, 2);
        byte[] longer = Arrays.copyOf(v.xres(), v.xres().length + 4);
        longer[longer.length - 1] = (byte) 0x7f;
        assertFalse(AkaMd5.verifyRes(v.xres(), longer),
                "BUG: longer RES with matching prefix accepted");
    }

    @Test
    void extractRes_caseInsensitiveAndProxyAuthStyle() {
        byte[] res = AkaMd5.parseHex("aabbccdd");
        String auth = "Digest username=\"u\", realm=\"ims.lab\", response=\""
                + AkaMd5.toHex(res) + "\", uri=\"sip:ims.lab\"";
        assertArrayEquals(res, AkaMd5.extractResFromAuthorization(auth));
        assertArrayEquals(res, AkaMd5.extractResFromAuthorization(
                "Digest RESPONSE=\"" + AkaMd5.toHex(res) + "\""));
    }

    @Test
    void extractRes_missingOrBroken() {
        assertEquals(null, AkaMd5.extractResFromAuthorization(null));
        assertEquals(null, AkaMd5.extractResFromAuthorization("Digest username=\"x\""));
        assertEquals(null, AkaMd5.extractResFromAuthorization("response=\"unclosed"));
    }

    @Test
    void wwwAuthenticate_containsNonceRandAutn() {
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, 3);
        String www = AkaMd5.wwwAuthenticate("ims.lab", v.rand(), v.autn());
        assertTrue(www.contains("AKAv1-MD5"));
        assertTrue(www.contains("realm=\"ims.lab\""));
        String nonce = AkaMd5.toHex(v.rand()) + AkaMd5.toHex(v.autn());
        assertTrue(www.contains(nonce));
        assertEquals(64, nonce.length());
    }

    @Test
    void parseHex_nullAndOddLengthDoNotThrow() {
        byte[] z = AkaMd5.parseHex(null);
        assertEquals(16, z.length);
        byte[] odd = AkaMd5.parseHex("abc");
        assertTrue(odd.length >= 1);
    }

    @Test
    void consecutiveVectors_randDiffers() {
        AkaMd5.Vector a = AkaMd5.generate(KI, OPC, AMF, 10);
        AkaMd5.Vector b = AkaMd5.generate(KI, OPC, AMF, 10);
        assertNotEquals(AkaMd5.toHex(a.rand()), AkaMd5.toHex(b.rand()));
        assertEquals(11, a.nextSqn());
    }

    @Test
    void sqnZeroAndLarge() {
        AkaMd5.Vector z = AkaMd5.generate(KI, OPC, AMF, 0);
        assertEquals(1, z.nextSqn());
        AkaMd5.Vector big = AkaMd5.generate(KI, OPC, AMF, (1L << 40) - 2);
        assertEquals((1L << 40) - 1, big.nextSqn());
    }
}
