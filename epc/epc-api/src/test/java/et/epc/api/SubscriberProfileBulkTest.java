package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SubscriberProfile validation and defaulting. */
class SubscriberProfileBulkTest {

    private static final PlmnId PLMN = new PlmnId("001", "01");

    @Test
    void defaultsEmptyApnListWhenNull() {
        SubscriberProfile p = new SubscriberProfile(
                new Imsi("1234567890"), null, true, PLMN, null, "internet",
                QosProfile.pilotDefault(), 0);
        assertEquals(List.of(), p.apns());
    }

    @Test
    void apnsAreDefensiveCopies() {
        List<ApnConfig> apns = List.of(ApnConfig.internet());
        SubscriberProfile p = new SubscriberProfile(
                new Imsi("1234567890"), "555", true, PLMN, apns, "internet",
                QosProfile.pilotDefault(), 0);
        assertEquals(1, p.apns().size());
    }

    @Test
    void unknownApnThrows() {
        SubscriberProfile p = new SubscriberProfile(
                new Imsi("1234567890"), null, true, PLMN, List.of(ApnConfig.internet()),
                "internet", QosProfile.pilotDefault(), 0);
        assertThrows(IllegalArgumentException.class, () -> p.apn("missing"));
    }

    @Test
    void apnLookupIsCaseInsensitive() {
        SubscriberProfile p = new SubscriberProfile(
                new Imsi("1234567890"), null, true, PLMN, List.of(ApnConfig.internet()),
                "internet", QosProfile.pilotDefault(), 0);
        assertEquals("internet", p.apn("INTERNET").apn());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enabledIsPreserved(boolean enabled) {
        SubscriberProfile p = new SubscriberProfile(
                new Imsi("1234567890"), "555", enabled, PLMN, List.of(), "internet",
                QosProfile.pilotDefault(), 0);
        assertEquals(enabled, p.enabled());
    }

    @Test
    void rejectsNullImsi() {
        assertThrows(NullPointerException.class, () -> new SubscriberProfile(
                null, null, true, PLMN, List.of(), "internet", QosProfile.pilotDefault(), 0));
    }

    @Test
    void rejectsNullHomePlmn() {
        assertThrows(NullPointerException.class, () -> new SubscriberProfile(
                new Imsi("1234567890"), null, true, null, List.of(), "internet",
                QosProfile.pilotDefault(), 0));
    }

    @Test
    void rejectsNullDefaultApn() {
        assertThrows(NullPointerException.class, () -> new SubscriberProfile(
                new Imsi("1234567890"), null, true, PLMN, List.of(), null,
                QosProfile.pilotDefault(), 0));
    }
}