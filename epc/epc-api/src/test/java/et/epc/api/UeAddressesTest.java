package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class UeAddressesTest {

    @Test
    void noneHasNoAddresses() {
        UeAddresses u = UeAddresses.none();
        assertNull(u.ipv4());
        assertNull(u.ipv6Prefix());
    }
}