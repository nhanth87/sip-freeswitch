package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionKeysBulkTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    @ParameterizedTest
    @ValueSource(ints = {5, 9, 15})
    void idFormatsImsiApnEbi(int ebi) {
        SessionKeys k = new SessionKeys(IMSI, "internet", new BearerId(ebi));
        assertEquals("001010000000001/internet/" + ebi, k.id());
    }

    @Test
    void rejectsNullImsi() {
        assertThrows(NullPointerException.class,
                () -> new SessionKeys(null, "internet", new BearerId(5)));
    }

    @Test
    void rejectsNullApn() {
        assertThrows(NullPointerException.class,
                () -> new SessionKeys(IMSI, null, new BearerId(5)));
    }

    @Test
    void rejectsNullBearer() {
        assertThrows(NullPointerException.class,
                () -> new SessionKeys(IMSI, "internet", null));
    }

    @Test
    void equalKeysShareId() {
        SessionKeys a = new SessionKeys(IMSI, "internet", new BearerId(5));
        SessionKeys b = new SessionKeys(new Imsi("001010000000001"), "internet", new BearerId(5));
        assertEquals(a.id(), b.id());
        assertEquals(a, b);
    }
}