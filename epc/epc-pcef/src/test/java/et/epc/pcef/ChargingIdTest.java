package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ChargingIdTest {

    @Test
    void positiveValueAccepted() {
        assertEquals(5, new ChargingId(5).value());
        assertEquals(Long.MAX_VALUE, new ChargingId(Long.MAX_VALUE).value());
    }

    @Test
    void zeroIsNoneSentinel() {
        assertEquals(0, new ChargingId(0).value());
        assertEquals(0, ChargingId.NONE.value());
    }

    @Test
    void negativeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChargingId(-1));
    }

    @Test
    void equalityByValue() {
        assertEquals(new ChargingId(7), new ChargingId(7));
    }
}