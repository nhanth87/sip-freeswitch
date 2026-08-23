package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpFteid;
import java.util.Objects;

/** SGW-U two-NIC bridge spec. Distinct marks and tables keep access vs core routes apart. */
public record LinuxSgwBridge(String accessDevice, String coreDevice,
                             int accessMark, int coreMark,
                             String accessTable, String coreTable,
                             String accessRoute, String coreRoute) {

    public LinuxSgwBridge {
        Objects.requireNonNull(accessDevice, "accessDevice");
        Objects.requireNonNull(coreDevice, "coreDevice");
        Objects.requireNonNull(accessTable, "accessTable");
        Objects.requireNonNull(coreTable, "coreTable");
        Objects.requireNonNull(accessRoute, "accessRoute");
        Objects.requireNonNull(coreRoute, "coreRoute");
        if (accessMark < 0 || coreMark < 0) {
            throw new IllegalArgumentException("fwmark values must be non-negative");
        }
        if (accessMark == coreMark) {
            throw new IllegalArgumentException("accessMark and coreMark must differ");
        }
    }

    public static LinuxSgwBridge standard() {
        return new LinuxSgwBridge("gtp-access", "gtp-core", 0x1, 0x2,
                "100", "200", "192.0.2.0/24", "198.51.100.0/24");
    }

    /** Derives bridge routes as /32 host routes from the two GTP peer F-TEIDs. */
    public static LinuxSgwBridge from(PfcpFteid accessPeer, PfcpFteid corePeer) {
        Objects.requireNonNull(accessPeer, "accessPeer");
        Objects.requireNonNull(corePeer, "corePeer");
        return new LinuxSgwBridge("gtp-access", "gtp-core", 0x1, 0x2,
                "100", "200",
                accessPeer.address().getHostAddress() + "/32",
                corePeer.address().getHostAddress() + "/32");
    }
}