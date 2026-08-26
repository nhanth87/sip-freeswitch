package et.epc.pgwc;

import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import et.epc.api.Fteid;
import et.epc.api.UeAddresses;
import et.epc.pcef.ChargingId;
import et.epc.pcef.PolicyDecision;
import java.util.List;
import java.util.Objects;

public record CreateSessionResult(
        UeAddresses ue,
        Fteid pgwS5c,
        Fteid pgwS5u,
        ChargingId chargingId,
        PolicyDecision policy,
        long pfcpSeid,
        List<Gtpv2Ies.PcoContainer> pco
) {
    public CreateSessionResult {
        Objects.requireNonNull(ue);
        Objects.requireNonNull(pgwS5c);
        Objects.requireNonNull(pgwS5u);
        Objects.requireNonNull(chargingId);
        Objects.requireNonNull(policy);
        pco = pco == null ? List.of() : List.copyOf(pco);
    }

    /** Compat ctor — no PCO (pre-W3 callers / tests). */
    public CreateSessionResult(
            UeAddresses ue, Fteid pgwS5c, Fteid pgwS5u,
            ChargingId chargingId, PolicyDecision policy, long pfcpSeid) {
        this(ue, pgwS5c, pgwS5u, chargingId, policy, pfcpSeid, List.of());
    }
}
