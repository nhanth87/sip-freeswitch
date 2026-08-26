package et.epc.sgwc;

import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import et.epc.api.Fteid;
import et.epc.api.UeAddresses;
import java.util.List;
import java.util.Objects;

/**
 * Decoded PGW-C S5-C {@code CREATE_SESSION_RESPONSE} — the fields the SGW-C
 * actually needs on the S5 leg: the UE PDN address plus the PGW S5-C / S5-U
 * F-TEIDs, and the PCO containers (IE 78) to relay to the MME. Charging id,
 * PCEF policy and Sxb SEID stay on the PGW-C and are not carried on S5-C.
 */
public record S5cSessionResult(UeAddresses ue, Fteid pgwS5c, Fteid pgwS5u,
                               List<Gtpv2Ies.PcoContainer> pco) {
    public S5cSessionResult {
        Objects.requireNonNull(ue);
        Objects.requireNonNull(pgwS5c);
        Objects.requireNonNull(pgwS5u);
        pco = pco == null ? List.of() : List.copyOf(pco);
    }

    /** Compat ctor — no PCO relay. */
    public S5cSessionResult(UeAddresses ue, Fteid pgwS5c, Fteid pgwS5u) {
        this(ue, pgwS5c, pgwS5u, List.of());
    }
}
