package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Imsi;
import org.junit.jupiter.api.Test;

class PcefServicePropertiesTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    @Test
    void chargingIdsMonotonicIncrease() {
        PcefService p = new PcefService();
        ChargingId a = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        ChargingId b = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        assertEquals(1, a.value());
        assertEquals(2, b.value());
    }

    @Test
    void openNullImsiRejected() {
        PcefService p = new PcefService();
        assertThrows(NullPointerException.class,
                () -> p.open(null, "internet", PcefService.Product.POSTPAID));
    }

    @Test
    void reportUnknownChargingIdThrows() {
        PcefService p = new PcefService();
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        p.close(id);
        UsageRecord u = new UsageRecord(IMSI, "internet", id, 1, 1);
        assertThrows(IllegalStateException.class, () -> p.reportUsage(u));
    }

    @Test
    void closeUnknownChargingIdThrows() {
        PcefService p = new PcefService();
        assertThrows(IllegalStateException.class, () -> p.close(new ChargingId(999)));
    }

    @Test
    void multipleReportsAccumulateInOrder() {
        PcefService p = new PcefService();
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        p.reportUsage(new UsageRecord(IMSI, "internet", id, 100, 50));
        p.reportUsage(new UsageRecord(IMSI, "internet", id, 200, 25));
        Cdr cdr = p.close(id);
        assertEquals(300, cdr.uplinkOctets());
        assertEquals(75, cdr.downlinkOctets());
    }

    @Test
    void closeAddsToCdrList() {
        PcefService p = new PcefService();
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        p.close(id);
        assertEquals(1, p.cdrs().size());
        assertEquals(id, p.cdrs().get(0).chargingId());
    }

    @Test
    void cdrListIsUnmodifiableSnapshot() {
        PcefService p = new PcefService();
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        p.close(id);
        java.util.List<Cdr> c = p.cdrs();
        assertThrows(UnsupportedOperationException.class,
                () -> c.add(new Cdr(id, IMSI, "x", 0, 0, java.time.Instant.now(), java.time.Instant.now())));
    }

    @Test
    void reconcileDetectsMismatch() {
        PcefService p = new PcefService();
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        UsageRecord up = new UsageRecord(IMSI, "internet", id, 100, 200);
        p.reportUsage(up);
        Cdr cdr = p.close(id);
        assertTrue(p.reconcile(up, cdr));
        Cdr liar = new Cdr(id, IMSI, "internet", 999, 200, cdr.start(), cdr.end());
        assertFalse(p.reconcile(up, liar));
        UsageRecord other = new UsageRecord(IMSI, "internet", new ChargingId(77), 100, 200);
        assertFalse(p.reconcile(other, cdr));
    }

    @Test
    void prepaidFailsClosedThenOpensWhenOcsLive() {
        PcefService p = new PcefService();
        assertThrows(IllegalStateException.class,
                () -> p.open(IMSI, "internet", PcefService.Product.PREPAID));
        p.setOcsLive(true);
        assertTrue(p.ocsLive());
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.PREPAID);
        assertEquals(1, id.value());
    }

    @Test
    void postpaidNeverRequiresOcs() {
        PcefService p = new PcefService();
        // OCS not live -> postpaid still opens (fail-open offline).
        ChargingId id = p.open(IMSI, "internet", PcefService.Product.POSTPAID);
        assertEquals(1, id.value());
    }

    @Test
    void authorizeImsReturnsQci5() {
        PcefService p = new PcefService();
        PolicyDecision d = p.authorize("ims");
        assertEquals(5, d.qos().qci());
        PolicyDecision dUpper = p.authorize("IMS");
        assertEquals(5, dUpper.qos().qci());
    }

    @Test
    void authorizeNonImsReturnsStaticDefaultQci9() {
        PcefService p = new PcefService();
        assertEquals(9, p.authorize("internet").qos().qci());
        assertEquals(9, p.authorize("whatever").qos().qci());
    }

    @Test
    void pcrfLiveFlagAccessors() {
        PcefService p = new PcefService();
        assertFalse(p.pcrfLive());
        p.setPcrfLive(true);
        assertTrue(p.pcrfLive());
    }
}