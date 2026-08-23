package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ImsiTest {

    @ParameterizedTest
    @ValueSource(strings = {"123456", "123456789012345", "001010000000001"})
    void validAtBounds(String digits) {
        assertEquals(digits, new Imsi(digits).digits());
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567890123456"})
    void rejectsWrongLength(String digits) {
        assertThrows(IllegalArgumentException.class, () -> new Imsi(digits));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345a", "123456 7890", "12-456-789", "0x12345"})
    void rejectsNonDigits(String digits) {
        assertThrows(IllegalArgumentException.class, () -> new Imsi(digits));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsNullOrBlank(String digits) {
        assertThrows(RuntimeException.class, () -> new Imsi(digits));
    }

    @Test
    void trimsWhitespace() {
        assertEquals("1234567890", new Imsi("  1234567890  ").digits());
    }

    @Test
    void toStringIsDigits() {
        assertEquals("1234567890", new Imsi("1234567890").toString());
    }
}