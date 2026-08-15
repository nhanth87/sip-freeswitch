package et.epc.pcef;

import et.epc.api.Imsi;
import java.util.Objects;

public record UsageRecord(Imsi imsi, String apn, ChargingId chargingId, long uplinkOctets, long downlinkOctets) {
    public UsageRecord {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        Objects.requireNonNull(chargingId);
        if (uplinkOctets < 0 || downlinkOctets < 0) {
            throw new IllegalArgumentException("octets");
        }
    }

    public long total() {
        return uplinkOctets + downlinkOctets;
    }
}
