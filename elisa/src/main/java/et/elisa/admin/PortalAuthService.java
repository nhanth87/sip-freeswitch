package et.elisa.admin;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.time.Instant;
import java.util.Optional;

@ApplicationScoped
public class PortalAuthService {
    public static final String COOKIE = "sf_session";
    public static final String ROLE_ADMIN = "ADMIN", ROLE_OPS = "OPS", ROLE_TENANT = "TENANT";
    private final String secret;
    private final boolean devLogin;

    public PortalAuthService(
            @ConfigProperty(name = "sf.admin.session-hmac-secret") String secret,
            @ConfigProperty(name = "sf.admin.dev-login", defaultValue = "false") boolean devLogin) {
        this.secret = secret;
        this.devLogin = devLogin;
    }

    public Optional<Session> login(String username, String password) {
        if (!devLogin || !"admin".equals(username) || !"admin".equals(password)) return Optional.empty();
        Instant expiresAt = Instant.now().plusSeconds(8 * 3600);
        String token = SignedSessionCookie.issue(secret, "admin", ROLE_ADMIN, null, expiresAt);
        return Optional.of(new Session(token, "admin", ROLE_ADMIN, null, expiresAt));
    }
    public Optional<Session> resolve(String cookieHeader) {
        String token = parseCookie(cookieHeader, COOKIE);
        return SignedSessionCookie.verify(secret, token)
                .map(c -> new Session(token, c.username(), c.role(), c.tenantId(), c.expiresAt()));
    }
    public static boolean mayOperate(Session session, boolean apiKeyOk) {
        return apiKeyOk || session != null && (ROLE_ADMIN.equals(session.role()) || ROLE_OPS.equals(session.role()));
    }
    public record Session(String token, String username, String role, String tenantId, Instant expiresAt) {
        public boolean isAdmin() { return ROLE_ADMIN.equals(role); }
    }
    public static String parseCookie(String cookieHeader, String name) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String item = part.trim(); int eq = item.indexOf('=');
            if (eq > 0 && name.equals(item.substring(0, eq).trim())) return item.substring(eq + 1).trim();
        }
        return null;
    }
    public static String setCookieHeader(String token) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=28800";
    }
    public static String clearCookieHeader() { return COOKIE + "=; Path=/; HttpOnly; Max-Age=0"; }
}
