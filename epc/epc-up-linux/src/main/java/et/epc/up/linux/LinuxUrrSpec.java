package et.epc.up.linux;

/**
 * PFCP session context of a URR: the owning SEID plus the reporting triggers
 * and thresholds decoded from the Create URR IEs. {@code urrId} keys stay the
 * decimal string form of the PFCP 32-bit URR-ID.
 */
public record LinuxUrrSpec(long seid, int reportingTriggers, long volumeThresholdBytes,
                           int timeThresholdSeconds, boolean uplink) {

    public LinuxUrrSpec {
        if (volumeThresholdBytes < 0) {
            throw new IllegalArgumentException("volumeThresholdBytes must be non-negative");
        }
        if (timeThresholdSeconds < 0) {
            throw new IllegalArgumentException("timeThresholdSeconds must be non-negative");
        }
    }
}
