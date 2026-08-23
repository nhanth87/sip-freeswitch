package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** High-volume edge coverage for the byte helpers used across EPS-AKA. */
class BytesMatrixTest {

    static Stream<String> hexCases() {
        Stream.Builder<String> b = Stream.builder();
        for (int i = 0; i < 256; i++) {
            b.add(String.format("%02x", i));
        }
        b.add("deadbeef");
        b.add("aa bb cc");
        b.add("AABBCC");
        b.add("00ff00ff00ff00ff");
        return b.build();
    }

    @ParameterizedTest
    @MethodSource("hexCases")
    void parseThenToHexRoundTrips(String hex) {
        byte[] parsed = Bytes.parseHex(hex);
        String normalized = Bytes.toHex(parsed);
        assertTrue(normalized.length() == parsed.length * 2);
        assertArrayEquals(parsed, Bytes.parseHex(normalized));
    }

    @Test
    void xorWithSingleByteKeyRepeats() {
        assertArrayEquals(new byte[]{0, 0, 0, 0},
                Bytes.xor(new byte[]{0x0f, 0x0f, 0x0f, 0x0f}, new byte[]{0x0f}));
    }

    @Test
    void xorIsItsOwnInverse() {
        byte[] a = Bytes.parseHex("deadbeef0123456789abcdef");
        byte[] key = Bytes.parseHex("0f0f");
        assertArrayEquals(a, Bytes.xor(Bytes.xor(a, key), key));
    }

    @Test
    void rotZeroIsIdentity() {
        byte[] a = Bytes.parseHex("0102030405060708");
        assertArrayEquals(a, Bytes.rotBytes(a, 0));
    }

    @Test
    void rotFullCycleIsIdentity() {
        byte[] a = Bytes.parseHex("0102030405060708");
        assertArrayEquals(a, Bytes.rotBytes(a, a.length));
    }

    @Test
    void concatEmptyParts() {
        assertArrayEquals(new byte[0], Bytes.concat());
        assertArrayEquals(new byte[]{1, 2}, Bytes.concat(new byte[]{}, new byte[]{1, 2}, new byte[]{}));
    }

    @Test
    void copyBounds() {
        byte[] a = Bytes.parseHex("deadbeef");
        assertArrayEquals(new byte[]{(byte) 0xbe, (byte) 0xef}, Bytes.copy(a, 2, 2));
        assertArrayEquals(new byte[0], Bytes.copy(a, 4, 0));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 255L, 256L, 65535L, 65536L, 0xFFFF_FFFF_FFFFL, 0x0123_4567_89ABL})
    void sqnLongRoundTrip(long sqn) {
        assertEquals(sqn, Bytes.bytesToSqn(Bytes.sqnToBytes(sqn)));
    }

    @Test
    void sqnToBytesSixOctetsBigEndian() {
        assertArrayEquals(new byte[]{0, 0, 0, 0, 1, 0}, Bytes.sqnToBytes(0x100L));
        assertArrayEquals(new byte[]{0x0f, 0x0f, 0x0f, 0x0f, 0x0f, 0x0f},
                Bytes.sqnToBytes(0x0f0f0f0f0f0fL));
    }

    @Test
    void toHexLowercase() {
        assertEquals("deadbeef", Bytes.toHex(Bytes.parseHex("DEADBEEF")));
    }
}