package et.elisa.tenant;

import et.elisa.admin.PortalAuthService;
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
public class TenantService {

    private static final Set<String> KINDS = Set.of("LAB", "ENTERPRISE", "TRUNK");
    private static final Set<String> TRANSPORTS = Set.of("SIP", "WS", "HTTP2", "HTTP3");

    @Inject PasswordHasher hasher;
    @Inject et.elisa.profile.ElisaProfileService sleeProfiles;

    /** Every tenant (boot warm of the MvnoTenant profile hot store). */
    @Transactional
    public List<SfTenant> listAll() {
        return SfTenant.listAll();
    }

    public List<SfTenant> listFor(PortalAuthService.Session session) {
        if (session == null) {
            return List.of();
        }
        if (session.isAdmin()) {
            return SfTenant.listAll();
        }
        if (PortalAuthService.ROLE_OPS.equals(session.role())) {
            return SfTenant.list("resellerId", session.username());
        }
        if (session.tenantId() != null) {
            return SfTenant.list("id", session.tenantId());
        }
        return List.of();
    }

    public boolean canAccess(PortalAuthService.Session session, String tenantId) {
        if (session == null || tenantId == null) {
            return false;
        }
        if (session.isAdmin()) {
            return true;
        }
        if (PortalAuthService.ROLE_OPS.equals(session.role())) {
            SfTenant t = SfTenant.findById(tenantId);
            return t != null && session.username().equals(t.resellerId);
        }
        return tenantId.equals(session.tenantId());
    }

    @Transactional
    public SfTenant upsert(PortalAuthService.Session actor,
                           String id,
                           String name,
                           String kind,
                           String sourceAddrAlias,
                           String httpApiKey,
                           boolean enabled,
                           String trunkTransport,
                           String trunkConfig) {
        if (actor == null || !(actor.isAdmin() || PortalAuthService.ROLE_OPS.equals(actor.role()))) {
            throw new WebApplicationException("operator required", Response.Status.FORBIDDEN);
        }
        if (id == null || id.isBlank()) {
            throw new WebApplicationException("tenantId required", Response.Status.BAD_REQUEST);
        }
        String kid = (kind == null || kind.isBlank() ? "LAB" : kind.trim().toUpperCase(Locale.ROOT));
        if (!KINDS.contains(kid)) {
            throw new WebApplicationException("invalid kind", Response.Status.BAD_REQUEST);
        }
        SfTenant existing = SfTenant.findById(id.trim());
        if (existing != null && PortalAuthService.ROLE_OPS.equals(actor.role())
                && !actor.username().equals(existing.resellerId)) {
            throw new WebApplicationException("not your tenant", Response.Status.FORBIDDEN);
        }
        SfTenant t = existing == null ? new SfTenant() : existing;
        if (existing == null) {
            t.id = id.trim();
            t.createdAt = Instant.now();
            if (PortalAuthService.ROLE_OPS.equals(actor.role())) {
                t.resellerId = actor.username();
            }
        }
        t.name = name == null || name.isBlank() ? t.id : name.trim();
        t.kind = kid;
        t.enabled = enabled;
        t.sourceAddrAlias = blankToNull(sourceAddrAlias);
        t.updatedAt = Instant.now();
        if (httpApiKey != null && !httpApiKey.isBlank()) {
            t.httpApiKey = httpApiKey.trim();
            t.httpApiKeyFp = PasswordHasher.fingerprint(t.httpApiKey);
        } else if (existing == null || t.httpApiKey == null || t.httpApiKey.isBlank()) {
            t.httpApiKey = hasher.generateApiKey();
            t.httpApiKeyFp = PasswordHasher.fingerprint(t.httpApiKey);
        }
        if ("TRUNK".equals(kid)) {
            String transport = trunkTransport == null ? "SIP" : trunkTransport.trim().toUpperCase(Locale.ROOT);
            if (!TRANSPORTS.contains(transport)) {
                throw new WebApplicationException("invalid trunk_transport", Response.Status.BAD_REQUEST);
            }
            t.trunkTransport = transport;
            t.trunkConfig = trunkConfig == null || trunkConfig.isBlank() ? "{}" : trunkConfig;
        } else {
            t.trunkTransport = null;
            t.trunkConfig = "{}";
        }
        if (existing == null) {
            t.persist();
        }
        if (sleeProfiles != null) {
            sleeProfiles.upsertTenant(t);
        }
        return t;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
