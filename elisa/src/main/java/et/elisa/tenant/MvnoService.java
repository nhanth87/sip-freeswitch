package et.elisa.tenant;

import et.elisa.admin.PortalAuthService;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCdr;
import et.elisa.persist.SfPortalUser;
import et.elisa.persist.SfTenant;
import et.elisa.profile.ElisaProfileService;
import et.elisa.security.ElisaAuthz;
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

/**
 * MVNO console operations with the three-level hierarchy enforced by {@link ElisaAuthz}
 * (jainslee-authz {@code platform-admin > tenant-admin > user}):
 *
 * <ul>
 *   <li><b>ADMIN</b> — every tenant: create, enable/disable, service config, any user;</li>
 *   <li><b>GD</b> — own tenant only: profile text, USER accounts, subscriber list, CDR;</li>
 *   <li><b>USER</b> — own subscriber (MSISDN) and own CDR.</li>
 * </ul>
 * Tenant service config is mirrored into the {@code MvnoTenant} SLEE profile after every
 * write, so the S-CSCF reads it from memory.
 */
@ApplicationScoped
public class MvnoService {

    private static final Set<String> ADMIN_CREATABLE = Set.of(
            PortalAuthService.ROLE_OPS, PortalAuthService.ROLE_GD, PortalAuthService.ROLE_USER);

    @Inject ElisaAuthz authz;
    @Inject PasswordHasher hasher;
    @Inject ElisaProfileService sleeProfiles;

    /** Service config editable by ADMIN; GD may only change name/description of its own tenant. */
    public record TenantForm(String id, String name, String description, boolean enabled, int maxCps,
                             String msisdnPrefix, String iscAsUri) {}

    @Transactional
    public List<SfTenant> tenants(PortalAuthService.Session s) {
        if (authz.isPlatformAdmin(s)) {
            return SfTenant.list("order by id");
        }
        authz.require(s, ElisaAuthz.SELF_READ);
        return s.tenantId() == null ? List.of() : SfTenant.list("id", s.tenantId());
    }

    @Transactional
    public SfTenant saveTenant(PortalAuthService.Session s, TenantForm f) {
        if (f.id() == null || f.id().isBlank()) {
            throw new WebApplicationException("tenantId required", Response.Status.BAD_REQUEST);
        }
        String id = f.id().trim();
        SfTenant t = SfTenant.findById(id);
        if (authz.isPlatformAdmin(s)) {
            authz.require(s, ElisaAuthz.TENANT_CONFIG_WRITE, id);
            if (t == null) {
                t = new SfTenant();
                t.id = id;
                t.kind = "ENTERPRISE";
                t.createdAt = Instant.now();
                t.httpApiKey = hasher.generateApiKey();
                t.httpApiKeyFp = PasswordHasher.fingerprint(t.httpApiKey);
            }
            t.enabled = f.enabled();
            t.maxCps = Math.max(0, f.maxCps());
            t.msisdnPrefix = blankToNull(f.msisdnPrefix());
            t.iscAsUri = blankToNull(f.iscAsUri());
        } else {
            // GD: own tenant, profile text only — service config stays with the operator.
            authz.require(s, ElisaAuthz.TENANT_PROFILE_WRITE, id);
            if (t == null) {
                throw new WebApplicationException("unknown tenant", Response.Status.NOT_FOUND);
            }
        }
        t.name = f.name() == null || f.name().isBlank() ? (t.name == null ? id : t.name) : f.name().trim();
        t.description = blankToNull(f.description());
        t.updatedAt = Instant.now();
        t.persist();
        sleeProfiles.upsertTenant(t);
        return t;
    }

    @Transactional
    public List<SfPortalUser> users(PortalAuthService.Session s) {
        if (authz.isPlatformAdmin(s)) {
            return SfPortalUser.list("order by username");
        }
        if (authz.isTenantAdmin(s)) {
            return SfPortalUser.list("tenantId = ?1 order by username", s.tenantId());
        }
        authz.require(s, ElisaAuthz.SELF_READ);
        return SfPortalUser.list("username", s.username());
    }

    @Transactional
    public SfPortalUser createUser(PortalAuthService.Session s, String username, String password, String role,
                                   String tenantId, String msisdn, String displayName) {
        if (username == null || username.isBlank() || password == null || password.length() < 8) {
            throw new WebApplicationException("username and a password of 8+ chars required",
                    Response.Status.BAD_REQUEST);
        }
        String r = role == null ? PortalAuthService.ROLE_USER : role.trim().toUpperCase(Locale.ROOT);
        String tid;
        if (authz.isPlatformAdmin(s)) {
            if (!ADMIN_CREATABLE.contains(r)) {
                throw new WebApplicationException("role must be OPS, GD or USER", Response.Status.BAD_REQUEST);
            }
            tid = PortalAuthService.ROLE_OPS.equals(r) ? null : blankToNull(tenantId);
        } else {
            // GD creates USER accounts of its own tenant only (never escalates).
            authz.require(s, ElisaAuthz.USERS_MANAGE, s.tenantId());
            if (!PortalAuthService.ROLE_USER.equals(r)) {
                throw new WebApplicationException("GD may only create USER accounts", Response.Status.FORBIDDEN);
            }
            tid = s.tenantId();
        }
        if (!PortalAuthService.ROLE_OPS.equals(r)) {
            if (tid == null || SfTenant.findById(tid) == null) {
                throw new WebApplicationException("existing tenantId required", Response.Status.BAD_REQUEST);
            }
        }
        String ms = blankToNull(msisdn);
        if (PortalAuthService.ROLE_USER.equals(r)) {
            if (ms == null) {
                throw new WebApplicationException("USER needs the MSISDN it owns", Response.Status.BAD_REQUEST);
            }
            ImsSubscriber sub = ImsSubscriber.find("msisdn = ?1 and tenantId = ?2", ms, tid).firstResult();
            if (sub == null) {
                throw new WebApplicationException("MSISDN is not a subscriber of this tenant",
                        Response.Status.BAD_REQUEST);
            }
        }
        if (SfPortalUser.findById(username.trim()) != null) {
            throw new WebApplicationException("username exists", Response.Status.CONFLICT);
        }
        SfPortalUser u = new SfPortalUser();
        u.username = username.trim();
        u.passwordHash = hasher.hash(password);
        u.role = r;
        u.tenantId = tid;
        u.msisdn = ms;
        u.displayName = blankToNull(displayName);
        u.httpApiKey = hasher.generateApiKey();
        u.httpApiKeyFp = PasswordHasher.fingerprint(u.httpApiKey);
        u.enabled = true;
        u.createdAt = Instant.now();
        u.updatedAt = u.createdAt;
        u.persist();
        return u;
    }

    @Transactional
    public List<ImsSubscriber> subscribers(PortalAuthService.Session s) {
        if (authz.isPlatformAdmin(s)) {
            return ImsSubscriber.list("order by impi");
        }
        if (authz.isTenantAdmin(s)) {
            authz.require(s, ElisaAuthz.SUBSCRIBERS_READ, s.tenantId());
            return ImsSubscriber.list("tenantId = ?1 order by impi", s.tenantId());
        }
        authz.require(s, ElisaAuthz.SELF_READ);
        String ms = ownMsisdn(s);
        return ms == null ? List.of() : ImsSubscriber.list("msisdn", ms);
    }

    /** Latest CDRs in the caller's scope (platform / tenant / own MSISDN). */
    @Transactional
    public List<SfCdr> cdrs(PortalAuthService.Session s, int limit) {
        int n = Math.max(1, Math.min(200, limit));
        if (authz.isPlatformAdmin(s)) {
            return SfCdr.find("order by startTime desc").page(0, n).list();
        }
        if (authz.isTenantAdmin(s)) {
            authz.require(s, ElisaAuthz.CDR_READ, s.tenantId());
            return SfCdr.find("tenantId = ?1 order by startTime desc", s.tenantId()).page(0, n).list();
        }
        authz.require(s, ElisaAuthz.SELF_READ);
        String ms = ownMsisdn(s);
        return ms == null ? List.of() : SfCdr.find("msisdn = ?1 order by startTime desc", ms).page(0, n).list();
    }

    String ownMsisdn(PortalAuthService.Session s) {
        SfPortalUser u = SfPortalUser.findById(s.username());
        return u == null ? null : u.msisdn;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
