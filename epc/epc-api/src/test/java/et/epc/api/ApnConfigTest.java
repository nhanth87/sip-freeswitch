package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApnConfigTest {

    @Test
    void internetDefaults() {
        ApnConfig a = ApnConfig.internet();
        assertEquals("internet", a.apn());
        assertEquals(PdnType.IPV4V6, a.pdnType());
        assertFalse(a.ims());
        assertNull(a.pcscfIpv4());
    }

    @Test
    void imsConfigCarriesPcscf() {
        ApnConfig a = ApnConfig.ims("192.0.2.10");
        assertEquals("ims", a.apn());
        assertTrue(a.ims());
        assertEquals("192.0.2.10", a.pcscfIpv4());
        assertEquals(5, a.qos().qci());
    }

    @Test
    void nullApnRejected() {
        assertThrows(NullPointerException.class, () ->
                new ApnConfig(null, PdnType.IPV4, QosProfile.pilotDefault(), false, null));
    }

    @Test
    void nullPdnTypeRejected() {
        assertThrows(NullPointerException.class, () ->
                new ApnConfig("x", null, QosProfile.pilotDefault(), false, null));
    }

    @Test
    void nullQosRejected() {
        assertThrows(NullPointerException.class, () ->
                new ApnConfig("x", PdnType.IPV4, null, false, null));
    }
}