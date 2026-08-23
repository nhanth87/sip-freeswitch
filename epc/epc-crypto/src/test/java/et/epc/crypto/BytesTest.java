package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BytesTest {

    @Test
    void xorRepeatsShorterKey() {
        assertArrayEquals(new byte[] {0x0e, 0x0d, 0x0c, 0x0b},
                Bytes.xor(new byte[] {1, 2, 3, 4}, new byte[] {0x0f}));
    }

    @Test
    void xorEqualLength() {
        assertArrayEquals(new byte[] {0, 0, 0},
                Bytes.xor(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
    }

    @Test
    void rotBytesWraps() {
        assertArrayEquals(new byte[] {3, 4, 1, 2},
                Bytes.rotBytes(new byte[] {1, 2, 3, 4}, 2));
    }

    @Test
    void concatParts() {
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5},
                Bytes.concat(new byte[] {1, 2}, new byte[] {3}, new byte[] {}, new byte[] {4, 5}));
    }

    @Test
    void copy() {
        assertArrayEquals(new byte[] {8, 7},
                Bytes.copy(new byte[] {9, 8, 7, 6, 5}, 1, 2));
    }

    @Test
    void parseHexStripsSpaces() {
        assertArrayEquals(new byte[] {(byte) 0xaa, (byte) 0xbb, 0x01},
                Bytes.parseHex("aa bb 01"));
        assertArrayEquals(new byte[] {(byte) 0xaa, (byte) 0xbb},
                Bytes.parseHex("aabb"));
    }

    @Test
    void toHexRoundTrip() {
        byte[] b = Bytes.parseHex("deadbeef");
        assertEquals("deadbeef", Bytes.toHex(b));
    }

    @Test
    void sqnRoundTrip() {
        long[] sqns = {0L, 1L, 255L, 256L, 0xFFFF_FFFF_FFFFL, 0x0123_4567_89ABL};
        for (long s : sqns) {
            assertEquals(s, Bytes.bytesToSqn(Bytes.sqnToBytes(s)));
        }
    }

    @Test
    void sqnToBytesIsSixOctetsBigEndian() {
        assertArrayEquals(new byte[] {0, 0, 0, 0, 1, 0},
                Bytes.sqnToBytes(0x100L));
    }
}