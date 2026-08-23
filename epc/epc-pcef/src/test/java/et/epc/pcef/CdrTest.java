package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Imsi;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CdrTest {

    @Test
    void cdrCarriesCountersAndWindow() {
        Imsi imsi = new Imsi("001010000000001");
        Instant start = Instant.parse("2026-08-18T00:00:00Z");
        Instant end = Instant.parse("2026-08-18T00:05:00Z");
        Cdr cdr = new Cdr(new ChargingId(1), imsi, "internet", 1000, 2000, start, end);
        assertEquals(1000, cdr.uplinkOctets());
        assertEquals(2000, cdr.downlinkOctets());
        assertEquals(start, cdr.start());
        assertEquals(end, cdr.end());
        assertEquals("internet", cdr.apn());
        assertEquals(imsi, cdr.imsi());
        assertEquals(1, cdr.chargingId().value());
    }

    @Test
    void nullFieldsRejected() {
        assertThrows(NullPointerException.class,
                () -> new Cdr(null, new Imsi("123456"), "x", 0, 0, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new Cdr(new ChargingId(1), null, "x", 0, 0, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new Cdr(new ChargingId(1), new Imsi("123456"), null, 0, 0, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new Cdr(new ChargingId(1), new Imsi("123456"), "x", 0, 0, null, Instant.now()));
        assertThrows(NullPointerException.class,
                () -> new Cdr(new ChargingId(1), new Imsi("123456"), "x", 0, 0, Instant.now(), null));
    }
}