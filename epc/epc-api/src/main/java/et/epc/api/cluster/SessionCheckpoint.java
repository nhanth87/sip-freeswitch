package et.epc.api.cluster;

import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.SessionKeys;
import et.epc.api.UeAddresses;
import java.io.Serializable;
import java.net.InetAddress;
import java.util.Objects;

/**
 * Flattened CP checkpoint — {@code String}/{@code long}/{@code int} only so
 * Infinispan {@code MarshallingAllowList} ({@code java.*}) can store it.
 * {@code sessionId} is role-namespaced ({@code sgw|imsi/apn/ebi}).
 */
public record SessionCheckpoint(
        String sessionId,
        String role,
        String imsiDigits,
        String apn,
        int ebi,
        String ownerNodeId,
        long generation,
        String ueIpv4,
        String sgwS1uKey,
        String sgwS5uKey,
        String pgwS5uKey,
        long chargingId,
        String pfcpSeidHex,
        int s11cTeid,
        long pfcpSeid
) implements Serializable {
    public SessionCheckpoint {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(role);
        Objects.requireNonNull(imsiDigits);
        Objects.requireNonNull(apn);
        Objects.requireNonNull(ownerNodeId);
    }

    public static String sgwId(SessionKeys keys) {
        return "sgw|" + keys.id();
    }

    public static String pgwId(SessionKeys keys) {
        return "pgw|" + keys.id();
    }

    public Imsi imsi() {
        return new Imsi(imsiDigits);
    }

    public SessionKeys keys() {
        return new SessionKeys(imsi(), apn, new et.epc.api.BearerId(ebi));
    }

    public Fteid sgwS1u() {
        return parseFteid(sgwS1uKey, 0);
    }

    public Fteid sgwS5u() {
        return parseFteid(sgwS5uKey, 4);
    }

    public Fteid pgwS5u() {
        return parseFteid(pgwS5uKey, 5);
    }

    public UeAddresses ue() {
        if (ueIpv4 == null || ueIpv4.isBlank()) {
            return UeAddresses.none();
        }
        try {
            InetAddress a = InetAddress.getByName(ueIpv4);
            if (a instanceof java.net.Inet4Address v4) {
                return new UeAddresses(v4, null);
            }
        } catch (Exception ignored) {
        }
        return UeAddresses.none();
    }

    public static Fteid parseFteid(String key, int interfaceType) {
        if (key == null || key.isBlank()) {
            return null;
        }
        int at = key.indexOf('@');
        if (at <= 0) {
            return null;
        }
        try {
            int teid = (int) Long.parseUnsignedLong(key.substring(0, at));
            InetAddress addr = InetAddress.getByName(key.substring(at + 1));
            return new Fteid(teid, addr, interfaceType);
        } catch (Exception e) {
            return null;
        }
    }
}
