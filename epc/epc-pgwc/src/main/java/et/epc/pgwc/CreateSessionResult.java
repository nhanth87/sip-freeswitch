package et.epc.pgwc;

import et.epc.api.Fteid;
import et.epc.api.UeAddresses;
import et.epc.pcef.ChargingId;
import et.epc.pcef.PolicyDecision;
import java.util.Objects;

public record CreateSessionResult(
        UeAddresses ue,
        Fteid pgwS5c,
        Fteid pgwS5u,
        ChargingId chargingId,
        PolicyDecision policy,
        long pfcpSeid
) {
    public CreateSessionResult {
        Objects.requireNonNull(ue);
        Objects.requireNonNull(pgwS5c);
        Objects.requireNonNull(pgwS5u);
        Objects.requireNonNull(chargingId);
        Objects.requireNonNull(policy);
    }
}
