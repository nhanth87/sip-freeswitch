package et.epc.pcef;

import et.epc.api.Imsi;
import java.time.Instant;
import java.util.Objects;

/** Offline Gz CDR. Byte counts must match UP usage reports. */
public record Cdr(
        ChargingId chargingId,
        Imsi imsi,
        String apn,
        long uplinkOctets,
        long downlinkOctets,
        Instant start,
        Instant end
) {
    public Cdr {
        Objects.requireNonNull(chargingId);
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
    }
}
