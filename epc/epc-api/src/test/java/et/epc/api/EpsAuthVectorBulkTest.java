package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** EpsAuthVector validation / accessor coverage (TS 33.401 AIA material). */
class EpsAuthVectorBulkTest {

    private static final byte[] RAND = new byte[16];
    private static final byte[] AUTN = new byte[16];
    private static final byte[] KASME = new byte[32];
    private static final byte[] XRES = new byte[8];

    @Test
    void validVectorAccessors() {
        EpsAuthVector v = new EpsAuthVector(RAND, XRES, AUTN, KASME, 42L);
        assertEquals(16, v.rand().length);
        assertEquals(8, v.xres().length);
        assertEquals(16, v.autn().length);
        assertEquals(32, v.kasme().length);
        assertEquals(42L, v.nextSqn());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, 17, 31, 33})
    void rejectsWrongRandLength(int len) {
        assertThrows(IllegalArgumentException.class, () -> new EpsAuthVector(
                new byte[len], XRES, new byte[16], new byte[32], 0L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, 17, 31, 33})
    void rejectsWrongAutnLength(int len) {
        assertThrows(IllegalArgumentException.class, () -> new EpsAuthVector(
                new byte[16], XRES, new byte[len], new byte[32], 0L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 31, 33})
    void rejectsWrongKasmeLength(int len) {
        assertThrows(IllegalArgumentException.class, () -> new EpsAuthVector(
                new byte[16], XRES, new byte[16], new byte[len], 0L));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class, () -> new EpsAuthVector(null, XRES, AUTN, KASME, 0L));
        assertThrows(NullPointerException.class, () -> new EpsAuthVector(RAND, null, AUTN, KASME, 0L));
        assertThrows(NullPointerException.class, () -> new EpsAuthVector(RAND, XRES, null, KASME, 0L));
        assertThrows(NullPointerException.class, () -> new EpsAuthVector(RAND, XRES, AUTN, null, 0L));
    }

    static Stream<Long> sqns() {
        return Stream.of(0L, 1L, Long.MAX_VALUE);
    }

    @ParameterizedTest
    @MethodSource("sqns")
    void nextSqnRoundTrips(long sqn) {
        assertEquals(sqn, new EpsAuthVector(RAND, XRES, AUTN, KASME, sqn).nextSqn());
    }
}