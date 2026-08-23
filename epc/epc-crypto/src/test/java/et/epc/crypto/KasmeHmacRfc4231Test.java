package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** RFC 4231 HMAC-SHA-256 known-answer vectors (Kasme KDF primitive). */
class KasmeHmacRfc4231Test {

    private static byte[] hex(String h) {
        return Bytes.parseHex(h);
    }

    @Test
    void testCase2() {
        byte[] key = "Jefe".getBytes(StandardCharsets.US_ASCII);
        byte[] data = "what do ya want for nothing?".getBytes(StandardCharsets.US_ASCII);
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Bytes.toHex(Kasme.hmacSha256(key, data)));
    }

    @Test
    void testCase3() {
        byte[] key = repeat((byte) 0xaa, 20);
        byte[] data = repeat((byte) 0xdd, 50);
        assertEquals("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe",
                Bytes.toHex(Kasme.hmacSha256(key, data)));
    }

    @Test
    void testCase6() {
        byte[] key = repeat((byte) 0xaa, 131);
        byte[] data = "Test Using Larger Than Block-Size Key - Hash Key First"
                .getBytes(StandardCharsets.US_ASCII);
        assertEquals("60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
                Bytes.toHex(Kasme.hmacSha256(key, data)));
    }

    private static byte[] repeat(byte b, int n) {
        byte[] out = new byte[n];
        java.util.Arrays.fill(out, b);
        return out;
    }
}