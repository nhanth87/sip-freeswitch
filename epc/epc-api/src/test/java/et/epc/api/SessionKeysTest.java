package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SessionKeysTest {

    @Test
    void idComposesImsiApnEbi() {
        SessionKeys k = new SessionKeys(new Imsi("001010000000001"), "internet", new BearerId(5));
        assertEquals("001010000000001/internet/5", k.id());
    }

    @Test
    void nullImsiRejected() {
        assertThrows(NullPointerException.class, () -> new SessionKeys(null, "apn", new BearerId(5)));
    }

    @Test
    void nullApnRejected() {
        assertThrows(NullPointerException.class, () -> new SessionKeys(new Imsi("123456"), null, new BearerId(5)));
    }

    @Test
    void nullBearerRejected() {
        assertThrows(NullPointerException.class, () -> new SessionKeys(new Imsi("123456"), "apn", null));
    }
}