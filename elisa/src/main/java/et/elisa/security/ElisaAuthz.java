package et.elisa.security;

import com.microjainslee.authz.AccountStatus;
import com.microjainslee.authz.AuthzException;
import com.microjainslee.authz.AuthzPolicy;
import com.microjainslee.authz.Principal;
import com.microjainslee.authz.RoleHierarchy;
import com.microjainslee.authz.Roles;
import et.elisa.admin.PortalAuthService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MVNO console RBAC on micro-jainslee {@code jainslee-authz}: the three-level tenant model
 * {@code platform-admin > tenant-admin > user} maps to Elisa portal roles
 * ADMIN (operator platform) &gt; GD (MVNO director) &gt; USER (MVNO member).
 *
 * <p>OPS (reseller operator) keeps its legacy cross-tenant reach over the tenants it
 * created; it is checked by {@link et.elisa.tenant.TenantService#canAccess}, not here.
 * Legacy TENANT logins are GD.
 */
@ApplicationScoped
public class ElisaAuthz {

    private static final Logger LOG = LogManager.getLogger(ElisaAuthz.class);

    /** Change any tenant's service config (CPS, concurrency, AS override, enable). */
    public static final String TENANT_CONFIG_WRITE = "tenant:config:write";
    /** GD-editable subset of the own tenant's config (display name, description). */
    public static final String TENANT_PROFILE_WRITE = "tenant:profile:write";
    public static final String TENANT_READ = "tenant:read";
    public static final String USERS_MANAGE = "users:manage";
    public static final String SUBSCRIBERS_READ = "subscribers:read";
    public static final String CDR_READ = "cdr:read";
    public static final String SELF_READ = "self:read";

    private final AuthzPolicy policy = AuthzPolicy.builder()
            .hierarchy(RoleHierarchy.DEFAULT)
            .crossTenantRole(Roles.PLATFORM_ADMIN)
            .grant(Roles.PLATFORM_ADMIN, "*")
            .grant(Roles.TENANT_ADMIN, TENANT_READ, TENANT_PROFILE_WRITE, USERS_MANAGE,
                    SUBSCRIBERS_READ, CDR_READ)
            .grant(Roles.USER, SELF_READ)
            .audit(d -> {
                if (!d.allowed()) {
                    LOG.info("authz deny subject={} action={} reason={}",
                            d.principal() == null ? "-" : d.principal().subject(), d.action(), d.reason());
                }
            })
            .build();

    public AuthzPolicy policy() {
        return policy;
    }

    /** Portal role → jainslee-authz role; unknown roles get none (fail closed). */
    public static String authzRole(String portalRole) {
        String r = portalRole == null ? "" : portalRole.trim().toUpperCase(Locale.ROOT);
        return switch (r) {
            case PortalAuthService.ROLE_ADMIN -> Roles.PLATFORM_ADMIN;
            case PortalAuthService.ROLE_GD, PortalAuthService.ROLE_TENANT -> Roles.TENANT_ADMIN;
            case PortalAuthService.ROLE_USER -> Roles.USER;
            default -> null;
        };
    }

    public Principal principal(PortalAuthService.Session s) {
        if (s == null) {
            return null;
        }
        String role = authzRole(s.role());
        return new Principal(s.username(), s.tenantId(), role == null ? Set.of() : Set.of(role), Set.of(),
                AccountStatus.ACTIVE, s.expiresAt(), Map.of());
    }

    public boolean has(PortalAuthService.Session s, String permission) {
        return policy.hasPermission(principal(s), permission);
    }

    public boolean isPlatformAdmin(PortalAuthService.Session s) {
        return policy.hasRole(principal(s), Roles.PLATFORM_ADMIN);
    }

    public boolean isTenantAdmin(PortalAuthService.Session s) {
        return policy.hasRole(principal(s), Roles.TENANT_ADMIN);
    }

    /** Permission + tenant ownership, or 401/403 for the HTTP layer. */
    public void require(PortalAuthService.Session s, String permission, String resourceTenantId) {
        try {
            Principal p = policy.authenticate(principal(s));
            policy.requirePermission(p, permission);
            policy.requireTenantAccess(p, resourceTenantId);
        } catch (AuthzException ex) {
            throw new WebApplicationException(ex.getMessage(), ex.httpStatus());
        }
    }

    public void require(PortalAuthService.Session s, String permission) {
        try {
            policy.requirePermission(policy.authenticate(principal(s)), permission);
        } catch (AuthzException ex) {
            throw new WebApplicationException(ex.getMessage(), ex.httpStatus());
        }
    }

    /** The tenant to act on — never trust a request-body tenant for non-admins. */
    public String effectiveTenant(PortalAuthService.Session s, String requested) {
        try {
            return policy.effectiveTenant(policy.authenticate(principal(s)), requested);
        } catch (AuthzException ex) {
            throw new WebApplicationException(ex.getMessage(), ex.httpStatus());
        }
    }
}
