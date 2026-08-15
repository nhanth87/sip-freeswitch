package et.epc.api;

import java.net.InetAddress;
import java.util.Objects;

/** Fully-qualified TEID: TEID is scoped by the receiving endpoint IP (TS 29.274). */
public record Fteid(int teid, InetAddress address, int interfaceType) {
    public Fteid {
        Objects.requireNonNull(address);
    }

    public String key() {
        return Integer.toUnsignedString(teid) + "@" + address.getHostAddress();
    }
}
