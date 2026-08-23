package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubscriberProfileTest {

    private static final PlmnId PLMN = new PlmnId("001", "01");
    private static final Imsi IMSI = new Imsi("001010000000001");

    private static SubscriberProfile profile() {
        return new SubscriberProfile(IMSI, "1234567890", true, PLMN,
                List.of(ApnConfig.internet(), ApnConfig.ims("192.0.2.10")),
                "internet", QosProfile.pilotDefault(), 0);
    }

    @Test
    void findApnByExactName() {
        assertEquals("internet", profile().apn("internet").apn());
        assertTrue(profile().apn("ims").ims());
    }

    @Test
    void apnLookupIgnoresCase() {
        assertEquals("internet", profile().apn("INTERNET").apn());
        assertEquals("ims", profile().apn("IMS").apn());
    }

    @Test
    void unknownApnThrows() {
        assertThrows(IllegalArgumentException.class, () -> profile().apn("nonexistent"));
    }

    @Test
    void apnsListIsPopulated() {
        assertEquals(2, profile().apns().size());
    }

    @Test
    void nullApnsBecomesEmpty() {
        SubscriberProfile p = new SubscriberProfile(IMSI, "1", true, PLMN, null,
                "internet", QosProfile.pilotDefault(), 0);
        assertEquals(0, p.apns().size());
    }

    @Test
    void nullDefaultApnRejected() {
        assertThrows(NullPointerException.class, () -> new SubscriberProfile(
                IMSI, "1", true, PLMN, List.of(ApnConfig.internet()), null,
                QosProfile.pilotDefault(), 0));
    }

    @Test
    void nullHomePlmnRejected() {
        assertThrows(NullPointerException.class, () -> new SubscriberProfile(
                IMSI, "1", true, null, List.of(ApnConfig.internet()), "internet",
                QosProfile.pilotDefault(), 0));
    }
}