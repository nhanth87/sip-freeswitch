package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import et.epc.api.Imsi;
import org.junit.jupiter.api.Test;

class UsageRecordTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    @Test
    void totalSumsBothDirections() {
        UsageRecord u = new UsageRecord(IMSI, "internet", new ChargingId(1), 100, 50);
        assertEquals(150, u.total());
    }

    @Test
    void negativeOctetsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new UsageRecord(IMSI, "internet", new ChargingId(1), -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new UsageRecord(IMSI, "internet", new ChargingId(1), 0, -1));
    }

    @Test
    void nullFieldsRejected() {
        assertThrows(NullPointerException.class,
                () -> new UsageRecord(null, "internet", new ChargingId(1), 0, 0));
        assertThrows(NullPointerException.class,
                () -> new UsageRecord(IMSI, null, new ChargingId(1), 0, 0));
        assertThrows(NullPointerException.class,
                () -> new UsageRecord(IMSI, "internet", null, 0, 0));
    }
}