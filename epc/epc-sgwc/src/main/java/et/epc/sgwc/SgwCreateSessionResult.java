package et.epc.sgwc;

import et.epc.api.Fteid;
import et.epc.api.UeAddresses;
import et.epc.pgwc.CreateSessionResult;
import java.util.Objects;

public record SgwCreateSessionResult(
        UeAddresses ue,
        Fteid sgwS11c,
        Fteid sgwS1u,
        Fteid sgwS5u,
        CreateSessionResult pgw
) {
    public SgwCreateSessionResult {
        Objects.requireNonNull(ue);
        Objects.requireNonNull(sgwS11c);
        Objects.requireNonNull(sgwS1u);
        Objects.requireNonNull(sgwS5u);
        Objects.requireNonNull(pgw);
    }
}
