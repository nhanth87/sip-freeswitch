package et.epc.pgwc.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;
import java.util.Objects;

/**
 * PGW-C session lifecycle event. Fired once a session is established (or torn
 * down, by convention with a zeroed {@code chargingId} surface) so other SBBs
 * and RAs can correlate charging/ipam state without reaching into GTP-C IEs.
 */
@EventType(name = "epc.pgwc.session", vendor = "et.epc", version = "1.0")
public final class PgwSessionEvent implements SleeEvent {

    private final String imsi;
    private final String apn;
    private final String ueIpv4;
    private final long chargingId;

    public PgwSessionEvent(String imsi, String apn, String ueIpv4, long chargingId) {
        this.imsi = Objects.requireNonNull(imsi, "imsi");
        this.apn = Objects.requireNonNull(apn, "apn");
        this.ueIpv4 = ueIpv4;
        this.chargingId = chargingId;
    }

    public String getImsi() {
        return imsi;
    }

    public String getApn() {
        return apn;
    }

    public String getUeIpv4() {
        return ueIpv4;
    }

    public long getChargingId() {
        return chargingId;
    }
}