package et.elisa.admin;

import et.elisa.persist.SfPortalUser;
import et.elisa.security.PasswordHasher;
import et.elisa.tenant.PortalUserService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PortalAuthService {
    public static final String COOKIE = "sf_session";
    public static final String ROLE_ADMIN = "ADMIN", ROLE_OPS = "OPS", ROLE_TENANT = "TENANT";

    private static final Logger LOG = Logger.getLogger(PortalAuthService.class);

    private final String secret;
    private final String bootstrapPassword;

    @Inject PortalUserService users;
    @Inject PasswordHasher hasher;

    public PortalAuthService(
            @ConfigProperty(name = "sf.admin.session-hmac-secret") String secret,
            @ConfigProperty(name = "sf.admin.bootstrap-password", defaultValue = "admin") String bootstrapPassword) {
        this.secret = secret;
        this.bootstrapPassword = bootstrapPassword;
    }

    void onStart(@Observes StartupEvent ev) {
        bootstrapAdminIfEmpty();
    }

    @Transactional
    void bootstrapAdminIfEmpty() {
        if (SfPortalUser.count() > 0) {
            return;
        }
        SfPortalUser admin = new SfPortalUser();
        admin.username = "admin";
        admin.passwordHash = hasher.hash(bootstrapPassword);
        admin.role = ROLE_ADMIN;
        admin.enabled = true;
        admin.displayName = "Bootstrap ADMIN";
        admin.createdAt = Instant.now();
        admin.updatedAt = admin.createdAt;
        admin.persist();
        LOG.warn("Bootstrapped ADMIN user 'admin' — change password immediately (sf.admin.bootstrap-password)");
    }

    public Optional<Session> login(String username, String password) {
        if (username == null || password == null) {
            return Optional.empty();
        }
        SfPortalUser u = users.find(username.trim());
        if (u == null || !u.enabled || !hasher.verify(password, u.passwordHash)) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.now().plusSeconds(8 * 3600);
        String token = SignedSessionCookie.issue(secret, u.username, u.role, u.tenantId, expiresAt);
        return Optional.of(new Session(token, u.username, u.role, u.tenantId, expiresAt));
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
        public boolean isAdmin() {
            return ROLE_ADMIN.equals(role);
        }

        public boolean isOps() {
            return ROLE_OPS.equals(role);
        }
    }

    public static String parseCookie(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String item = part.trim();
            int eq = item.indexOf('=');
            if (eq > 0 && name.equals(item.substring(0, eq).trim())) {
                return item.substring(eq + 1).trim();
            }
        }
        return null;
    }

    public static String setCookieHeader(String token) {
        return COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=28800";
    }

    public static String clearCookieHeader() {
        return COOKIE + "=; Path=/; HttpOnly; Max-Age=0";
    }
}
