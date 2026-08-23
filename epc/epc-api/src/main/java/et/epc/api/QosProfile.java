package et.epc.api;

public record QosProfile(int qci, int arpPriority, boolean preemptionCapability,
                         boolean preemptionVulnerability, long apnAmbrUlBps, long apnAmbrDlBps,
                         long ueAmbrUlBps, long ueAmbrDlBps) implements java.io.Serializable {
    public static QosProfile pilotDefault() {
        long g = 1_000_000_000L;
        return new QosProfile(9, 8, false, true, g, g, g, g);
    }
}
