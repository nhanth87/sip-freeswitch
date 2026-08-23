package et.epc.pcrf;

import et.epc.api.QosProfile;
import java.util.List;
import java.util.Objects;

/**
 * Decision returned by the PCRF for one IP-CAN session: installed PccRules,
 * an optional volume usage-monitor threshold, and the default EPS bearer QoS.
 */
public record PccDecision(
        List<PccRule> rules,
        Long usageMonitorThreshold,
        QosProfile defaultBearerQos) implements java.io.Serializable {

    public PccDecision {
        rules = rules == null ? List.of() : List.copyOf(rules);
        Objects.requireNonNull(defaultBearerQos);
        if (usageMonitorThreshold != null && usageMonitorThreshold < 0) {
            throw new IllegalArgumentException("usage monitor threshold must be >= 0");
        }
    }

    public static PccDecision empty(QosProfile defaultBearerQos) {
        return new PccDecision(List.of(), null, defaultBearerQos);
    }

    public PccRule ruleByName(String name) {
        for (PccRule r : rules) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return null;
    }
}