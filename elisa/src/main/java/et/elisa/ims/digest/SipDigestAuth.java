package et.elisa.ims.digest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

/**
 * SIP Digest MD5 (RFC 2617 / RFC 3261) for dashboard SIP clients (Linphone / Zoiper).
 * IMS AKA stays {@code AKAv1-MD5} on {@code ScscfRegisterSbb}.
 */
public final class SipDigestAuth {

    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SipDigestAuth() {}

    public static String newNonce() {
        byte[] n = new byte[16];
        RNG.nextBytes(n);
        return HEX.formatHex(n);
    }

    public static String wwwAuthenticate(String realm, String nonce) {
        String r = realm == null || realm.isBlank() ? "ims.lab" : realm.trim();
        return "Digest realm=\"" + r + "\", nonce=\"" + nonce
                + "\", algorithm=MD5, qop=\"auth\"";
    }

    public static boolean isAka(String authorization) {
        if (authorization == null) {
            return false;
        }
        return authorization.toUpperCase(Locale.ROOT).contains("AKAV1-MD5");
    }

    public static boolean isMd5Digest(String authorization) {
        if (authorization == null || authorization.isBlank() || isAka(authorization)) {
            return false;
        }
        return authorization.regionMatches(true, 0, "Digest", 0, 6);
    }

    public static boolean verify(String authorization, String ha1, String method, String expectedNonce) {
        if (authorization == null || ha1 == null || ha1.isBlank() || expectedNonce == null) {
            return false;
        }
        String nonce = param(authorization, "nonce");
        if (nonce == null || !expectedNonce.equals(nonce)) {
            return false;
        }
        String uri = param(authorization, "uri");
        String response = param(authorization, "response");
        if (uri == null || response == null) {
            return false;
        }
        String ha2 = md5Hex((method == null ? "REGISTER" : method) + ":" + uri);
        String qop = param(authorization, "qop");
        String expected;
        if (qop != null && !qop.isBlank()) {
            String nc = param(authorization, "nc");
            String cnonce = param(authorization, "cnonce");
            if (nc == null || cnonce == null) {
                return false;
            }
            expected = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);
        } else {
            expected = md5Hex(ha1 + ":" + nonce + ":" + ha2);
        }
        return expected.equalsIgnoreCase(response);
    }

    public static String nonceOf(String wwwOrAuthorization) {
        return param(wwwOrAuthorization, "nonce");
    }

    static String param(String header, String name) {
        String lower = header.toLowerCase(Locale.ROOT);
        String key = name.toLowerCase(Locale.ROOT) + "=";
        int i = indexOfToken(lower, key);
        if (i < 0) {
            return null;
        }
        int v = i + key.length();
        while (v < header.length() && header.charAt(v) == ' ') {
            v++;
        }
        if (v < header.length() && header.charAt(v) == '"') {
            int end = header.indexOf('"', v + 1);
            return end < 0 ? null : header.substring(v + 1, end);
        }
        int end = v;
        while (end < header.length()) {
            char c = header.charAt(end);
            if (c == ',' || c == ' ') {
                break;
            }
            end++;
        }
        return header.substring(v, end);
    }

    /** Match {@code name=} as a Digest token, not a substring of {@code cnonce}. */
    static int indexOfToken(String lowerHeader, String key) {
        int from = 0;
        while (from < lowerHeader.length()) {
            int found = lowerHeader.indexOf(key, from);
            if (found < 0) {
                return -1;
            }
            if (found == 0 || !isTokenChar(lowerHeader.charAt(found - 1))) {
                return found;
            }
            from = found + 1;
        }
        return -1;
    }

    private static boolean isTokenChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    public static String md5Hex(String raw) {
        try {
            byte[] dig = MessageDigest.getInstance("MD5").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
