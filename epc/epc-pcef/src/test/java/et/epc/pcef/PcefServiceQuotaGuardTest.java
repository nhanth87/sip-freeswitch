package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Imsi;
import org.junit.jupiter.api.Test;

/**
 * Gy overrun guard: pure function on {@link PcefService}, no OCS transport.
 * With {@code epc.pcef.ocs.enabled=false} (default) no quota is ever granted,
 * so the guard must stay false and let offline charging proceed.
 */
class PcefServiceQuotaGuardTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private ChargingId openWithUsage(PcefService pcef, long uplink, long downlink) {
        ChargingId id = pcef.open(IMSI, "internet", PcefService.Product.POSTPAID);
        pcef.reportUsage(new UsageRecord(IMSI, "internet", id, uplink, downlink));
        return id;
    }

    @Test
    void noQuotaConfiguredIsNeverOver() {
        PcefService pcef = new PcefService();
        ChargingId id = openWithUsage(pcef, 1_000_000L, 2_000_000L);
        assertFalse(pcef.isOverGrantedQuota(id, 0L));
        assertFalse(pcef.isOverGrantedQuota(id, -5L));
    }

    @Test
    void underQuotaIsNotOver() {
        PcefService pcef = new PcefService();
        ChargingId id = openWithUsage(pcef, 100, 150);
        assertFalse(pcef.isOverGrantedQuota(id, 500L));
    }

    @Test
    void reachingGrantedQuotaIsOver() {
        PcefService pcef = new PcefService();
        ChargingId id = openWithUsage(pcef, 300, 200);
        pcef.applyGrant(id, 500L);
        assertTrue(pcef.isOverGrantedQuota(id, pcef.grantedOctets(id)));
        assertTrue(pcef.isOverGrantedQuota(id, 400L));
    }

    @Test
    void unknownOrNoneSessionIsNeverOver() {
        PcefService pcef = new PcefService();
        assertFalse(pcef.isOverGrantedQuota(new ChargingId(123), 500L));
        assertFalse(pcef.isOverGrantedQuota(ChargingId.NONE, 500L));
        assertFalse(pcef.isOverGrantedQuota(null, 500L));
    }
}