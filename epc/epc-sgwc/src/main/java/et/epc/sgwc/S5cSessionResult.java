package et.epc.sgwc;

import et.epc.api.Fteid;
import et.epc.api.UeAddresses;
import java.util.Objects;

/**
 * Decoded PGW-C S5-C {@code CREATE_SESSION_RESPONSE} — the fields the SGW-C
 * actually needs on the S5 leg: the UE PDN address plus the PGW S5-C / S5-U
 * F-TEIDs. Charging id, PCEF policy and Sxb SEID stay on the PGW-C and are not
 * carried on S5-C.
 */
public record S5cSessionResult(UeAddresses ue, Fteid pgwS5c, Fteid pgwS5u) {
    public S5cSessionResult {
        Objects.requireNonNull(ue);
        Objects.requireNonNull(pgwS5c);
        Objects.requireNonNull(pgwS5u);
    }
}