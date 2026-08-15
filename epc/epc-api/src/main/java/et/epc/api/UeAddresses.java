package et.epc.api;

import java.net.Inet4Address;
import java.net.Inet6Address;

public record UeAddresses(Inet4Address ipv4, Inet6Address ipv6Prefix) {
    public static UeAddresses none() {
        return new UeAddresses(null, null);
    }
}
