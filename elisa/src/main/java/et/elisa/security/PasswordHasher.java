package et.elisa.security;

import at.favre.lib.crypto.bcrypt.BCrypt;
import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.HexFormat;

@ApplicationScoped
public class PasswordHasher {
    private final SecureRandom random = new SecureRandom();

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(12, password.toCharArray());
    }

    public boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isBlank()) {
            return false;
        }
        return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
    }

    public String generateApiKey() {
        byte[] buf = new byte[24];
        random.nextBytes(buf);
        return "sf_" + HexFormat.of().formatHex(buf);
    }

    public static String fingerprint(String apiKey) {
        if (apiKey == null || apiKey.length() < 4) {
            return "";
        }
        return apiKey.substring(apiKey.length() - 4);
    }
}
