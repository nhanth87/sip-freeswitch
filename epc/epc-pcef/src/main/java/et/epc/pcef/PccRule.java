package et.epc.pcef;

import java.util.Objects;

/**
 * Light PCEF-side PCC rule model. Kept local to {@code epc-pcef} so the PCEF
 * never depends on {@code epc-pcrf}; the field set mirrors
 * {@code et.epc.pcrf.PccRule} one-to-one for trivial boundary mapping.
 */
public record PccRule(
        String name,
        int qci,
        int arpPriority,
        boolean preemptionCapable,
        boolean preemptionVulnerable,
        long mbrUlBps,
        long mbrDlBps,
        long gbrUlBps,
        long gbrDlBps,
        Integer ratingGroup,
        String serviceId,
        String flowDescription,
        boolean gatingOpen) {

    public PccRule {
        Objects.requireNonNull(name);
        if (name.isBlank()) {
            throw new IllegalArgumentException("rule name must not be blank");
        }
        if (qci < 1 || qci > 255) {
            throw new IllegalArgumentException("qci out of range: " + qci);
        }
        if (arpPriority < 1 || arpPriority > 15) {
            throw new IllegalArgumentException("arp priority out of range: " + arpPriority);
        }
        if (mbrUlBps < 0 || mbrDlBps < 0 || gbrUlBps < 0 || gbrDlBps < 0) {
            throw new IllegalArgumentException("bit rates must be >= 0");
        }
        flowDescription = flowDescription == null ? "" : flowDescription;
    }
}