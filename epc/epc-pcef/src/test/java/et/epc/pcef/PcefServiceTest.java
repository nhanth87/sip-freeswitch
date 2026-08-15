package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Imsi;
import org.junit.jupiter.api.Test;

class PcefServiceTest {

    @Test
    void usageReconcilesWithCdr() {
        PcefService pcef = new PcefService();
        Imsi imsi = new Imsi("001010000000001");
        ChargingId id = pcef.open(imsi, "internet", PcefService.Product.POSTPAID);
        UsageRecord u = new UsageRecord(imsi, "internet", id, 100, 200);
        pcef.reportUsage(u);
        Cdr cdr = pcef.close(id);
        assertTrue(pcef.reconcile(u, cdr));
        assertEquals(300, cdr.uplinkOctets() + cdr.downlinkOctets());
    }

    @Test
    void prepaidFailsClosedWithoutOcs() {
        PcefService pcef = new PcefService();
        assertThrows(IllegalStateException.class,
                () -> pcef.open(new Imsi("001010000000001"), "internet", PcefService.Product.PREPAID));
        pcef.setOcsLive(true);
        ChargingId id = pcef.open(new Imsi("001010000000001"), "internet", PcefService.Product.PREPAID);
        assertEquals(1, id.value());
    }
}
