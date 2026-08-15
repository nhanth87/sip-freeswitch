package et.epc.pgwc;

import et.epc.api.Imsi;
import et.epc.api.PdnType;
import et.epc.api.UeAddresses;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** IPv4/IPv6 pool. Allocation is durable-checkpointed by the caller, not on GTP-U. */
public final class Ipam {

    private final AtomicInteger nextHost = new AtomicInteger(2);
    private final ConcurrentHashMap<String, UeAddresses> byImsi = new ConcurrentHashMap<>();
    private final byte[] prefix = {(byte) 10, 64, 0, 0};

    public UeAddresses allocate(Imsi imsi, PdnType type) {
        Objects.requireNonNull(imsi);
        return byImsi.computeIfAbsent(imsi.digits(), k -> {
            try {
                int host = nextHost.getAndIncrement();
                Inet4Address v4 = null;
                Inet6Address v6 = null;
                if (type == PdnType.IPV4 || type == PdnType.IPV4V6) {
                    v4 = (Inet4Address) Inet4Address.getByAddress(new byte[] {
                            prefix[0], prefix[1], (byte) ((host >> 8) & 0xff), (byte) (host & 0xff)});
                }
                if (type == PdnType.IPV6 || type == PdnType.IPV4V6) {
                    byte[] a = new byte[16];
                    a[0] = 0x20;
                    a[1] = 0x01;
                    a[2] = 0x0d;
                    a[3] = (byte) 0xb8;
                    a[15] = (byte) (host & 0xff);
                    v6 = (Inet6Address) Inet6Address.getByAddress(a);
                }
                return new UeAddresses(v4, v6);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    public void release(Imsi imsi) {
        byImsi.remove(imsi.digits());
    }

    public int size() {
        return byImsi.size();
    }
}
