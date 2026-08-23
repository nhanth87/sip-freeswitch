package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PdnTypeBulkTest {

    @ParameterizedTest
    @EnumSource(PdnType.class)
    void enumValuesAreStable(PdnType type) {
        assertEquals(type, PdnType.valueOf(type.name()));
    }

    @Test
    void exactlyThreeValues() {
        assertEquals(3, PdnType.values().length);
    }

    @ParameterizedTest
    @EnumSource(PdnType.class)
    void nameIsUppercase(PdnType type) {
        assertEquals(type.name().toUpperCase(), type.name());
    }
}