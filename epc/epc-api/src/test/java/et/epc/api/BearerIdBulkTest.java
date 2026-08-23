package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Volume/edge coverage for BearerId EBI int validation. */
class BearerIdBulkTest {

    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15})
    void acceptsValidEbiRange(int ebi) {
        assertEquals(ebi, new BearerId(ebi).ebi());
    }

    static Stream<Integer> invalid() {
        // Representative invalid EBIs only — a full 0..4 sweep plus out-of-range probes.
        return Stream.of(
                0, 1, 2, 3, 4, 16, 17, 18, 19, 20, 21, 22, 23,
                -1, -5, 100, 1000, Integer.MAX_VALUE, Integer.MIN_VALUE);
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void rejectsEbiOutsideFiveThroughFifteen(int ebi) {
        assertThrows(IllegalArgumentException.class, () -> new BearerId(ebi));
    }

    @Test
    void recordAccessorNamedEbi() {
        BearerId id = new BearerId(9);
        assertEquals(9, id.ebi());
    }
}