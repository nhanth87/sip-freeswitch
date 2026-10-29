package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import et.epc.api.Imsi;
import org.junit.jupiter.api.Test;

class SubscriberRecordTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private static SubscriberRecord rec(long sqn) {
        return new SubscriberRecord(et.epc.hss.SubscriberRecordTest.profile(), sqn);
    }

    static et.epc.api.SubscriberProfile profile() {
        return new et.epc.api.SubscriberProfile(
                IMSI, "1234567890", true, new et.epc.api.PlmnId("001", "01"),
                java.util.List.of(et.epc.api.ApnConfig.internet()), "internet",
                et.epc.api.QosProfile.pilotDefault(), 0);
    }

    @Test
    void reserveSqnIncrementsFromSeed() {
        SubscriberRecord r = rec(10);
        assertEquals(et.epc.crypto.Sqn.next(10), r.reserveSqn());
        assertEquals(et.epc.crypto.Sqn.next(et.epc.crypto.Sqn.next(10)), r.reserveSqn());
    }

    @Test
    void forceSqnNeverDecreases() {
        SubscriberRecord r = rec(10);
        r.forceSqn(50);
        assertEquals(50, r.sqn());
        r.forceSqn(5);
        assertEquals(50, r.sqn());
        r.forceSqn(60);
        assertEquals(60, r.sqn());
    }

    @Test
    void servingMmeAndPurgedAndScscfDefaults() {
        SubscriberRecord r = rec(0);
        assertNull(r.servingMmeHost());
        assertEquals(false, r.purged());
        assertNull(r.imsScscfUri());
    }

    @Test
    void idMatchesImsi() {
        assertEquals(IMSI.digits(), rec(0).id().imsi().digits());
    }

    @Test
    void nullProfileRejected() {
        assertThrows(NullPointerException.class, () -> new SubscriberRecord(null, 0));
    }
}