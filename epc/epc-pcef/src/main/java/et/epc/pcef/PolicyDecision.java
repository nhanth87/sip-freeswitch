package et.epc.pcef;

import et.epc.api.QosProfile;
import java.util.Objects;

public record PolicyDecision(QosProfile qos, boolean gatingOpen, String ruleName) {
    public PolicyDecision {
        Objects.requireNonNull(qos);
        Objects.requireNonNull(ruleName);
    }

    public static PolicyDecision staticDefault() {
        return new PolicyDecision(QosProfile.pilotDefault(), true, "static-default");
    }

    public static PolicyDecision ims() {
        return new PolicyDecision(new QosProfile(5, 1, true, false,
                1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L), true, "ims-qci5");
    }
}
