package et.epc.up.linux;

import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * UE-IP → urrId attribution index so kernel counter elements (keyed by UE
 * address in the nft set) can be charged to the right URR. Maintained by the
 * PFCP install/delete path in lockstep with {@link LinuxUrrRegistry}.
 */
public final class UrrIpIndex {

    private final ConcurrentMap<String, String> urrToIp = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> ipToUrr = new ConcurrentHashMap<>();

    /** Register (or move) the UE IPv4 counted for a URR. */
    public void register(String urrId, InetAddress ueIpv4) {
        Objects.requireNonNull(urrId, "urrId");
        Objects.requireNonNull(ueIpv4, "ueIpv4");
        String ip = ueIpv4.getHostAddress();
        unregister(urrId);
        urrToIp.put(urrId, ip);
        ipToUrr.put(ip, urrId);
    }

    public void unregister(String urrId) {
        String ip = urrToIp.remove(Objects.requireNonNull(urrId, "urrId"));
        if (ip != null) {
            ipToUrr.remove(ip, urrId);
        }
    }

    /** urrId counted under a kernel counter element IP, or {@code null}. */
    public String urrForIp(String ip) {
        return ip == null ? null : ipToUrr.get(ip);
    }

    public String ipForUrr(String urrId) {
        return urrToIp.get(urrId);
    }

    public int size() {
        return urrToIp.size();
    }
}
