package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.Inet4Address;
import java.net.Inet6Address;
import org.junit.jupiter.api.Test;

class UeAddressesBulkTest {

    @Test
    void noneHasBothNull() {
        UeAddresses none = UeAddresses.none();
        assertNull(none.ipv4());
        assertNull(none.ipv6Prefix());
    }

    @Test
    void noneInstancesAreEqual() {
        assertEquals(UeAddresses.none(), UeAddresses.none());
    }

    @Test
    void distinctNoneIsNewInstance() {
        assertNull(UeAddresses.none().ipv4());
    }

    static Inet4Address v4(String host) throws Exception {
        return (Inet4Address) Inet4Address.getByName(host);
    }

    static Inet6Address v6(String host) throws Exception {
        return (Inet6Address) Inet6Address.getByName(host);
    }

    @Test
    void ipv4OnlyCarriesV4() throws Exception {
        UeAddresses u = new UeAddresses(v4("10.64.0.2"), null);
        assertNotNull(u.ipv4());
        assertNull(u.ipv6Prefix());
    }

    @Test
    void ipv6OnlyCarriesV6() throws Exception {
        UeAddresses u = new UeAddresses(null, v6("2001:db8::2"));
        assertNull(u.ipv4());
        assertNotNull(u.ipv6Prefix());
    }

    @Test
    void dualStackCarriesBoth() throws Exception {
        UeAddresses u = new UeAddresses(v4("10.64.0.2"), v6("2001:db8::2"));
        assertNotNull(u.ipv4());
        assertNotNull(u.ipv6Prefix());
    }
}