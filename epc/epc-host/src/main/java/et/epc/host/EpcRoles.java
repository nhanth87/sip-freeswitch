package et.epc.host;

/**
 * One image → many services. Mirrors Elisa {@code SF_IMS_*}: config only, never a
 * combined CSCF+SGW process with Elisa.
 */
public record EpcRoles(boolean hss, boolean sgwc, boolean pgwc,
                       boolean pcrf, boolean pcef, boolean up) {
    public EpcRoles {
        if (!hss && !sgwc && !pgwc && !pcrf && !pcef && !up) {
            throw new IllegalArgumentException("at least one EPC_ROLE_* must be true");
        }
    }

    public static EpcRoles fromEnv(boolean hss, boolean sgwc, boolean pgwc,
                                   boolean pcrf, boolean pcef, boolean up) {
        return new EpcRoles(hss, sgwc, pgwc, pcrf, pcef, up);
    }

    /** Convenience for combined PGW-C + HSS nodes unchanged from the 3-flag era. */
    public static EpcRoles classic(boolean hss, boolean sgwc, boolean pgwc) {
        // 3-flag era: PGW-C embeds its PCEF (wirePcef() == pgwc), so the standalone
        // pcef / pcrf / up roles stay off — UP is never inside a C-plane JVM.
        return new EpcRoles(hss, sgwc, pgwc, false, false, false);
    }

    public boolean wireS6a() {
        return hss;
    }

    public boolean wireGtpS11() {
        return sgwc;
    }

    public boolean wireGtpS5() {
        return sgwc || pgwc;
    }

    public boolean wirePfcpSxa() {
        return sgwc;
    }

    public boolean wirePfcpSxb() {
        return pgwc;
    }

    public boolean wirePcef() {
        // PGW-C embeds a PCEF (dedicated bearer/PCC edge). Standalone epc-pcef is wirePcefStandalone().
        return pgwc;
    }

    /** PCRF: policy/charging rule engine + Gx/Rx. */
    public boolean wirePcrf() {
        return pcrf;
    }

    /** Standalone PCEF service (epc-pcef), distinct from the PGW-C-embedded PCEF. */
    public boolean wirePcefStandalone() {
        return pcef;
    }

    /** User-plane (epc-up-linux, GTP-U/S1-U/S5-U) — control-plane see-through. */
    public boolean wireUp() {
        return up;
    }

    /** Diameter apps (S6a/Cx/Gx/Rx) are only prepared when some Diameter peer role is on. */
    public boolean wireDiameter() {
        return hss || pcrf || pgwc || pcef;
    }
}
