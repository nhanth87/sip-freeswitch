package et.elisa.tenant;

import et.elisa.admin.PortalAuthService;
import et.elisa.persist.SfPortalUser;
import et.elisa.persist.SfTenant;
import et.elisa.security.PasswordHasher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@ApplicationScoped
public class PortalUserService {

    private static final Set<String> CREATABLE = Set.of("TENANT", "OPS");

    @Inject PasswordHasher hasher;

    public List<SfPortalUser> listAll() {
        return SfPortalUser.listAll();
    }

    @Transactional
    public SfPortalUser create(PortalAuthService.Session actor,
                               String username,
                               String password,
                               String role,
                               String tenantId,
                               String displayName) {
        if (actor == null || !actor.isAdmin()) {
            throw new WebApplicationException("ADMIN only", Response.Status.FORBIDDEN);
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new WebApplicationException("username/password required", Response.Status.BAD_REQUEST);
        }
        String r = role == null ? "TENANT" : role.trim().toUpperCase(Locale.ROOT);
        if (!CREATABLE.contains(r)) {
            throw new WebApplicationException("role must be TENANT or OPS", Response.Status.BAD_REQUEST);
        }
        if ("TENANT".equals(r) && (tenantId == null || tenantId.isBlank())) {
            throw new WebApplicationException("tenantId required for TENANT", Response.Status.BAD_REQUEST);
        }
        if ("TENANT".equals(r) && SfTenant.findById(tenantId) == null) {
            throw new WebApplicationException("unknown tenant", Response.Status.BAD_REQUEST);
        }
        if (SfPortalUser.findById(username.trim()) != null) {
            throw new WebApplicationException("username exists", Response.Status.CONFLICT);
        }
        SfPortalUser u = new SfPortalUser();
        u.username = username.trim();
        u.passwordHash = hasher.hash(password);
        u.role = r;
        u.tenantId = "TENANT".equals(r) ? tenantId.trim() : null;
        u.displayName = displayName;
        u.httpApiKey = hasher.generateApiKey();
        u.httpApiKeyFp = PasswordHasher.fingerprint(u.httpApiKey);
        u.enabled = true;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        u.persist();
        return u;
    }

    @Transactional
    public boolean authenticate(String username, String password) {
        SfPortalUser u = SfPortalUser.findById(username);
        return u != null && u.enabled && hasher.verify(password, u.passwordHash);
    }

    public SfPortalUser find(String username) {
        return SfPortalUser.findById(username);
    }
}
