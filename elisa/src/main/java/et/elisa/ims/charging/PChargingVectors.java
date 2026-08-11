package et.elisa.ims.charging;

import com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * TS 24.229 {@code P-Charging-Vector} — lab ICID stamp / preserve (no Rf/OCS).
 *
 * <p>Format: {@code icid-value=&lt;token&gt;;orig-ioi=&lt;realm&gt;}
 */
public final class PChargingVectors {

    public static final String HEADER = ImsSipHeaderNames.P_CHARGING_VECTOR;
    /** Dialplan / FS CDR correlate (alongside X-Elisa-Sip-Call-Id). */
    public static final String X_ELISA_ICID = "X-Elisa-Icid";

    private PChargingVectors() {}

    /**
     * Ensure {@code P-Charging-Vector} on outbound INVITE IMS headers.
     * Preserves an existing vector; otherwise generates {@code icid-value} + {@code orig-ioi}.
     *
     * @return icid-value token (never null)
     */
    public static String ensureInvite(Map<String, List<String>> imsHeaders, String sipCallId, String origIoi) {
        Map<String, List<String>> ims = imsHeaders;
        if (ims == null) {
            throw new IllegalArgumentException("imsHeaders");
        }
        String existing = first(ims, HEADER);
        if (existing != null && !existing.isBlank()) {
            String icid = extractIcidValue(existing);
            if (icid == null || icid.isBlank()) {
                icid = synthesizeIcid(sipCallId);
                String rebuilt = "icid-value=" + icid + appendOrigIoi(existing, origIoi);
                ims.put(HEADER, List.of(rebuilt));
            }
            ims.putIfAbsent(X_ELISA_ICID, List.of(icid));
            return icid;
        }
        String icid = synthesizeIcid(sipCallId);
        String ioi = blankToRealm(origIoi);
        ims.put(HEADER, List.of("icid-value=" + icid + ";orig-ioi=" + ioi));
        ims.put(X_ELISA_ICID, List.of(icid));
        return icid;
    }

    /** Mutable copy helper for SBB hot path. */
    public static Map<String, List<String>> copyIms(Map<String, List<String>> src) {
        return new LinkedHashMap<>(src == null ? Map.of() : src);
    }

    public static String extractIcidValue(String pChargingVector) {
        return extractParam(pChargingVector, "icid-value=");
    }

    public static String extractOrigIoi(String pChargingVector) {
        return extractParam(pChargingVector, "orig-ioi=");
    }

    public static String extractTermIoi(String pChargingVector) {
        return extractParam(pChargingVector, "term-ioi=");
    }

    /** Public for CDR when FS omit P-Charging-Vector but Call-ID is known. */
    public static String synthesizeIcid(String sipCallId) {
        if (sipCallId != null && !sipCallId.isBlank()) {
            // Stable per Call-ID so P and S agree without shared store on retry.
            UUID u = UUID.nameUUIDFromBytes(sipCallId.getBytes(StandardCharsets.UTF_8));
            return u.toString().replace("-", "");
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String extractParam(String header, String keyEq) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String s = header.trim();
        int idx = indexOfIgnoreCase(s, keyEq);
        if (idx < 0) {
            return null;
        }
        int start = idx + keyEq.length();
        if (start < s.length() && s.charAt(start) == '"') {
            int end = s.indexOf('"', start + 1);
            return end > start ? s.substring(start + 1, end).trim() : null;
        }
        int end = s.indexOf(';', start);
        String token = (end < 0 ? s.substring(start) : s.substring(start, end)).trim();
        return token.isEmpty() ? null : token;
    }

    private static String appendOrigIoi(String existing, String origIoi) {
        if (indexOfIgnoreCase(existing, "orig-ioi=") >= 0) {
            return "";
        }
        return ";orig-ioi=" + blankToRealm(origIoi);
    }

    private static String blankToRealm(String origIoi) {
        if (origIoi == null || origIoi.isBlank()) {
            return "ims.lab";
        }
        String s = origIoi.trim();
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            int at = s.indexOf('@');
            if (at > 0 && at + 1 < s.length()) {
                String host = s.substring(at + 1);
                int semi = host.indexOf(';');
                int port = host.indexOf(':');
                int cut = host.length();
                if (semi >= 0) {
                    cut = Math.min(cut, semi);
                }
                if (port >= 0) {
                    cut = Math.min(cut, port);
                }
                return host.substring(0, cut);
            }
        }
        return s;
    }

    private static String first(Map<String, List<String>> ims, String name) {
        List<String> v = ims.get(name);
        if (v == null || v.isEmpty()) {
            return null;
        }
        return v.getFirst();
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
