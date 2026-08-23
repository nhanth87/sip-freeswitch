package et.epc.up.linux;

import java.time.Instant;
import java.util.Objects;

/** A periodic URR report: {@code reportedBytes} is the delta since the last report, not cumulative. */
public record LinuxUrrReport(String urrId, long reportedBytes, long cumulativeBytes, Instant reportedAt) {

    public LinuxUrrReport {
        Objects.requireNonNull(urrId, "urrId");
        Objects.requireNonNull(reportedAt, "reportedAt");
        if (reportedBytes < 0 || cumulativeBytes < 0) {
            throw new IllegalArgumentException("byte counts must be non-negative");
        }
    }
}