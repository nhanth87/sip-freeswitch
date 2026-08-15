package et.elisa.cmp;

/**
 * Canonical SIP Call-ID for CMP / CallSession keys.
 * FreeSWITCH Sofia URL-encodes {@code @} as {@code %40} in channel variables;
 * S-CSCF CMP uses the wire Call-ID with a literal {@code @}. One key per dialog.
 */
public final class SipCallIds {

    private SipCallIds() {}

    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw.trim();
        if (s.indexOf('%') < 0) {
            return s;
        }
        return s.replace("%40", "@")
                .replace("%3A", ":")
                .replace("%3a", ":");
    }
}
