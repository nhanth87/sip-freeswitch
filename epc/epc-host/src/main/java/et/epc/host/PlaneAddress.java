package et.epc.host;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** The address an EPC node advertises in its F-TEIDs (GTP-C and GTP-U). */
final class PlaneAddress {

    private PlaneAddress() {}

    /** {@code configured} when set, else the first non-loopback IPv4 of this host, else loopback. */
    static InetAddress resolve(String configured) {
        try {
            if (configured != null && !configured.isBlank()) {
                return InetAddress.getByName(configured.trim());
            }
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return a;
                    }
                }
            }
        } catch (SocketException | java.net.UnknownHostException e) {
            throw new IllegalStateException("epc.local.address unusable: " + configured, e);
        }
        return InetAddress.getLoopbackAddress();
    }
}
