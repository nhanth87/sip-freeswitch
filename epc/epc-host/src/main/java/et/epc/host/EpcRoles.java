package et.epc.host;

/**
 * One image → many services. Mirrors Elisa {@code SF_IMS_*}: config only, never a
 * combined CSCF+SGW process with Elisa.
 */
public record EpcRoles(boolean hss, boolean sgwc, boolean pgwc) {
    public EpcRoles {
        if (!hss && !sgwc && !pgwc) {
            throw new IllegalArgumentException("at least one EPC_ROLE_* must be true");
        }
    }

    public static EpcRoles fromEnv(boolean hss, boolean sgwc, boolean pgwc) {
        return new EpcRoles(hss, sgwc, pgwc);
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
        return pgwc;
    }
}
