package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApnConfigBulkTest {

    @Test
    void internetIsIpv4v6NonIms() {
        ApnConfig a = ApnConfig.internet();
        assertEquals("internet", a.apn());
        assertEquals(PdnType.IPV4V6, a.pdnType());
        assertFalse(a.ims());
        assertNull(a.pcscfIpv4());
        assertNotNull(a.qos());
    }

    @Test
    void imsCarriesPcscfAndQci5() {
        ApnConfig a = ApnConfig.ims("192.0.2.10");
        assertEquals("ims", a.apn());
        assertTrue(a.ims());
        assertEquals("192.0.2.10", a.pcscfIpv4());
        assertEquals(5, a.qos().qci());
    }

    @Test
    void imsQosIsGbr() {
        ApnConfig a = ApnConfig.ims("192.0.2.10");
        assertEquals(1, a.qos().arpPriority());
        assertTrue(a.qos().preemptionCapability());
    }

    @Test
    void pilotDefaultQosIsQci9() {
        assertEquals(9, QosProfile.pilotDefault().qci());
    }
}