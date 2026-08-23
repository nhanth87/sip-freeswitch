package et.epc.pcef;

import et.epc.api.QosProfile;
import java.util.List;
import java.util.Objects;

public record PolicyDecision(QosProfile qos, boolean gatingOpen, String ruleName, List<PccRule> rules) {
    public PolicyDecision {
        Objects.requireNonNull(qos);
        Objects.requireNonNull(ruleName);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /** Backward-compatible 3-arg form: decision with no installed rules. */
    public PolicyDecision(QosProfile qos, boolean gatingOpen, String ruleName) {
        this(qos, gatingOpen, ruleName, List.of());
    }

    public static PolicyDecision staticDefault() {
        return new PolicyDecision(QosProfile.pilotDefault(), true, "static-default");
    }

    public static PolicyDecision ims() {
        return new PolicyDecision(new QosProfile(5, 1, true, false,
                1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L), true, "ims-qci5");
    }

    public static PolicyDecision of(QosProfile qos, boolean gatingOpen, String ruleName, List<PccRule> rules) {
        return new PolicyDecision(qos, gatingOpen, ruleName, rules);
    }
}
