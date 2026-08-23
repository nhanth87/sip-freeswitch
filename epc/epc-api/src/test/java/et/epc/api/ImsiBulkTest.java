package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Volume/edge coverage for IMSI validation and normalization. */
class ImsiBulkTest {

    static Stream<String> valid() {
        Stream.Builder<String> b = Stream.builder();
        for (int len = 6; len <= 15; len++) {
            String d = "123456789012345".substring(0, len);
            b.add(d);
        }
        b.add("001010000000001");
        b.add(" 1234567890 ");
        b.add("  1234567890  ");
        return b.build();
    }

    @ParameterizedTest
    @MethodSource("valid")
    void acceptsValidLengths(String digits) {
        assertEquals(digits.trim(), new Imsi(digits).digits());
    }

    static Stream<String> tooShortOrLong() {
        Stream.Builder<String> b = Stream.builder();
        for (int len = 0; len < 6; len++) {
            b.add("1".repeat(len));
        }
        for (int len = 16; len <= 22; len++) {
            b.add("1".repeat(len));
        }
        return b.build();
    }

    @ParameterizedTest
    @MethodSource("tooShortOrLong")
    void rejectsWrongLengths(String digits) {
        assertThrows(IllegalArgumentException.class, () -> new Imsi(digits));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345a", "123456 7890", "12-456-789", "0x12345",
            "123456.789", "123456 789", "00345678901234a", "αβγδεζ"})
    void rejectsNonDigits(String digits) {
        assertThrows(IllegalArgumentException.class, () -> new Imsi(digits));
    }

    static Stream<String> nullLike() {
        return Stream.of(null, "", "   ", "\t", "\n");
    }

    @ParameterizedTest
    @MethodSource("nullLike")
    void rejectsNullLike(String digits) {
        assertThrows(RuntimeException.class, () -> new Imsi(digits));
    }

    @Test
    void equalityUsesNormalizedDigits() {
        assertEquals(new Imsi("1234567890"), new Imsi(" 1234567890 "));
    }

    @Test
    void hashCodeMatchesNormalized() {
        assertEquals(new Imsi("1234567890").hashCode(), new Imsi(" 1234567890 ").hashCode());
    }
}