package et.elisa.admin;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Stateless HMAC-SHA256 browser session cookie. */
public final class SignedSessionCookie {
    private static final String VERSION = "v1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private SignedSessionCookie() {}

    public static String issue(String secret, String username, String role, String tenantId, Instant expiresAt) {
        String payload = username + "\n" + role + "\n" + (tenantId == null ? "" : tenantId)
                + "\n" + expiresAt.getEpochSecond();
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return VERSION + "." + body + "." + B64.encodeToString(hmac(secret, VERSION + "." + body));
    }

    public static Optional<Claims> verify(String secret, String token) {
        if (secret == null || secret.isBlank() || token == null) return Optional.empty();
        String[] p = token.split("\\.", 3);
        if (p.length != 3 || !VERSION.equals(p[0])
                || !constantTimeEquals(B64.encodeToString(hmac(secret, p[0] + "." + p[1])), p[2])) return Optional.empty();
        try {
            String[] lines = new String(B64D.decode(p[1]), StandardCharsets.UTF_8).split("\n", -1);
            if (lines.length != 4) return Optional.empty();
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(lines[3]));
            return Instant.now().isBefore(expiresAt)
                    ? Optional.of(new Claims(lines[0], lines[1], lines[2].isEmpty() ? null : lines[2], expiresAt))
                    : Optional.empty();
        } catch (RuntimeException ex) { return Optional.empty(); }
    }

    public record Claims(String username, String role, String tenantId, Instant expiresAt) {}
    private static byte[] hmac(String secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) { throw new IllegalStateException("session HMAC unavailable", ex); }
    }
    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8), right = b.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int diff = 0; for (int i = 0; i < left.length; i++) diff |= left[i] ^ right[i];
        return diff == 0;
    }
}
