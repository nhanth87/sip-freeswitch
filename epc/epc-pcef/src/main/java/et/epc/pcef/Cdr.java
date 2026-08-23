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
        Instant end,
        Kind kind
) {
    /** Lifecycle marker used by {@link JdbcCdrStore} to reconcile start/interim/stop rows. */
    public enum Kind { START, INTERIM, STOP }

    public Cdr {
        Objects.requireNonNull(chargingId);
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        kind = kind == null ? Kind.STOP : kind;
    }

    /** Backward-compatible final (STOP) CDR constructor. */
    public Cdr(ChargingId chargingId, Imsi imsi, String apn,
               long uplinkOctets, long downlinkOctets, Instant start, Instant end) {
        this(chargingId, imsi, apn, uplinkOctets, downlinkOctets, start, end, Kind.STOP);
    }
}
