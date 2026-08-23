package et.epc.up.linux;

import java.time.Instant;
import java.util.Objects;

/** On-demand URR counter snapshot (PFCP Query-URR). Does not advance the report baseline. */
public record LinuxUrrUsage(String urrId, long cumulativeBytes, long baselineBytes, Instant sampledAt) {

    public LinuxUrrUsage {
        Objects.requireNonNull(urrId, "urrId");
        Objects.requireNonNull(sampledAt, "sampledAt");
        if (cumulativeBytes < 0 || baselineBytes < 0) {
            throw new IllegalArgumentException("byte counts must be non-negative");
        }
    }

    /** Bytes accumulated since the last reported baseline. */
    public long deltaBytes() {
        return cumulativeBytes - baselineBytes;
    }
}