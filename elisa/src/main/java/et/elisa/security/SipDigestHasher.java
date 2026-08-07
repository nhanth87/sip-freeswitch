package et.elisa.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** FreeSWITCH directory digest: MD5(user:realm:password) → {@code a1-hash}. */
public final class SipDigestHasher {

    private SipDigestHasher() {}

    public static String a1Hash(String user, String realm, String password) {
        if (user == null || realm == null || password == null) {
            throw new IllegalArgumentException("user, realm, password required");
        }
        String raw = user + ":" + realm + ":" + password;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /** True when value looks like a stored a1-hash (32 hex chars). */
    public static boolean looksLikeA1Hash(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int i = 0; i < 32; i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }
}
