package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BearerIdTest {

    @ParameterizedTest
    @ValueSource(ints = {5, 6, 12, 15})
    void validRange(int ebi) {
        assertEquals(ebi, new BearerId(ebi).ebi());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 16, 100, -5})
    void rejectsOutOfRange(int ebi) {
        assertThrows(IllegalArgumentException.class, () -> new BearerId(ebi));
    }
}