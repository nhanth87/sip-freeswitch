package et.epc.pgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Imsi;
import et.epc.api.PdnType;
import org.junit.jupiter.api.Test;

class IpamTest {

    @Test
    void allocatesDistinctIpv4PerImsi() throws Exception {
        Ipam ipam = new Ipam();
        var a = ipam.allocate(new Imsi("001010000000001"), PdnType.IPV4);
        var b = ipam.allocate(new Imsi("001010000000002"), PdnType.IPV4);
        assertNotNull(a.ipv4());
        assertNotNull(b.ipv4());
        assertTrue(!a.ipv4().getHostAddress().equals(b.ipv4().getHostAddress()));
        assertNull(a.ipv6Prefix());
        assertEquals(2, ipam.size());
    }

    @Test
    void sameImsiIsStable() {
        Ipam ipam = new Ipam();
        Imsi imsi = new Imsi("001010000000001");
        var a = ipam.allocate(imsi, PdnType.IPV4);
        var b = ipam.allocate(imsi, PdnType.IPV4);
        assertEquals(a, b);
        assertEquals(1, ipam.size());
    }

    @Test
    void ipv6OnlyYieldsNoIpv4() {
        Ipam ipam = new Ipam();
        var a = ipam.allocate(new Imsi("001010000000001"), PdnType.IPV6);
        assertNull(a.ipv4());
        assertNotNull(a.ipv6Prefix());
    }

    @Test
    void dualStackYieldsBothFamilies() {
        Ipam ipam = new Ipam();
        var a = ipam.allocate(new Imsi("001010000000001"), PdnType.IPV4V6);
        assertNotNull(a.ipv4());
        assertNotNull(a.ipv6Prefix());
    }

    @Test
    void releaseFreesSlot() {
        Ipam ipam = new Ipam();
        Imsi imsi = new Imsi("001010000000001");
        ipam.allocate(imsi, PdnType.IPV4);
        assertEquals(1, ipam.size());
        ipam.release(imsi);
        assertEquals(0, ipam.size());
    }

    @Test
    void firstIpv4IsDotTwo() throws Exception {
        Ipam ipam = new Ipam();
        var a = ipam.allocate(new Imsi("001010000000001"), PdnType.IPV4);
        assertEquals("10.64.0.2", a.ipv4().getHostAddress());
    }
}