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
        long pfcpSeid,
        String mmeS11Key,
        String sgwS5cKey,
        String pgwS5cKey,
        String pgwcPeer,
        int s5cTeid
) implements Serializable {
    public SessionCheckpoint {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(role);
        Objects.requireNonNull(imsiDigits);
        Objects.requireNonNull(apn);
        Objects.requireNonNull(ownerNodeId);
    }

    /** Checkpoint without control-plane peers (rows and call sites from before they were kept). */
    public SessionCheckpoint(
            String sessionId, String role, String imsiDigits, String apn, int ebi,
            String ownerNodeId, long generation, String ueIpv4, String sgwS1uKey,
            String sgwS5uKey, String pgwS5uKey, long chargingId, String pfcpSeidHex,
            int s11cTeid, long pfcpSeid) {
        this(sessionId, role, imsiDigits, apn, ebi, ownerNodeId, generation, ueIpv4,
                sgwS1uKey, sgwS5uKey, pgwS5uKey, chargingId, pfcpSeidHex, s11cTeid, pfcpSeid,
                null, null, null, null, 0);
    }

    /**
     * The GTP-C peers of this session, so any node of the cluster can answer it (TS 29.274
     * §5.5.2: a response carries the peer's TEID). SGW: MME S11, own S5-C, PGW S5-C and the
     * PGW-C address; PGW: the SGW's S5-C F-TEID and our own S5-C TEID ({@code s5cTeid}).
     */
    public SessionCheckpoint withControlPeers(Fteid mmeS11, Fteid sgwS5c, Fteid pgwS5c,
                                              String pgwcPeer, int s5cTeid) {
        return new SessionCheckpoint(sessionId, role, imsiDigits, apn, ebi, ownerNodeId, generation,
                ueIpv4, sgwS1uKey, sgwS5uKey, pgwS5uKey, chargingId, pfcpSeidHex, s11cTeid, pfcpSeid,
                key(mmeS11), key(sgwS5c), key(pgwS5c), pgwcPeer, s5cTeid);
    }

    private static String key(Fteid f) {
        return f == null ? null : f.key();
    }

    /** TS 29.274 §8.22 interface types 10 / 6 / 7. */
    public Fteid mmeS11() {
        return parseFteid(mmeS11Key, 10);
    }

    public Fteid sgwS5c() {
        return parseFteid(sgwS5cKey, 6);
    }

    public Fteid pgwS5c() {
        return parseFteid(pgwS5cKey, 7);
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

    /** TS 29.274 §8.22 interface type 1 = S1-U SGW GTP-U. */
    public Fteid sgwS1u() {
        return parseFteid(sgwS1uKey, 1);
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
