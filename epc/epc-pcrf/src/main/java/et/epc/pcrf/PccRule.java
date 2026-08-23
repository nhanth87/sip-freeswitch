package et.epc.pcrf;

import java.util.Objects;

/**
 * A single PCC rule: the unit the PCEF programs into the bearer. Mirrors the
 * {@code epc_pcc_rule} durable row (non-secret only). QCI 1–255, ARP priority
 * 1–15, bit rates in bits/s.
 *
 * <p>{@code flowDescription} is a single IPFilterRule-style flow description
 * string ("permit out ip from any to any"). Durable storage keeps it in
 * {@code flow_desc_json} as a JSON array; {@link JdbcPolicyRepository} unwraps
 * the first element.</p>
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
        boolean gatingOpen) implements java.io.Serializable {

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

    public static PccRule defaultInternet() {
        return new PccRule("internet-default", 9, 8, false, true,
                1_000_000_000L, 1_000_000_000L, 0L, 0L,
                null, null, "permit out ip from any to any", true);
    }

    public static PccRule imsSignalQci5() {
        return new PccRule("ims-signal-qci5", 5, 1, true, false,
                1_000_000_000L, 1_000_000_000L, 0L, 0L,
                null, "ims-sip", "permit out ip from any to any", true);
    }

    public static PccRule imsVoiceQci1() {
        return new PccRule("ims-voice-qci1", 1, 1, true, false,
                512_000_000L, 512_000_000L, 512_000_000L, 512_000_000L,
                null, "ims-rtp", "permit out udp from any to any", true);
    }
}