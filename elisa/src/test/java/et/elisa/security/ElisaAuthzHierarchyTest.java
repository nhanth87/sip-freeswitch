package et.elisa.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.admin.PortalAuthService;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** ADMIN > GD > USER on jainslee-authz (platform-admin > tenant-admin > user). */
class ElisaAuthzHierarchyTest {

    final ElisaAuthz authz = new ElisaAuthz();

    static PortalAuthService.Session s(String role, String tenant) {
        return new PortalAuthService.Session("t", role.toLowerCase() + "-1", role, tenant,
                Instant.now().plusSeconds(600));
    }

    @Test
    void adminReachesEveryTenantAndConfig() {
        PortalAuthService.Session admin = s("ADMIN", null);
        assertDoesNotThrow(() -> authz.require(admin, ElisaAuthz.TENANT_CONFIG_WRITE, "mvno-a"));
        assertDoesNotThrow(() -> authz.require(admin, ElisaAuthz.USERS_MANAGE, "mvno-b"));
        assertTrue(authz.isTenantAdmin(admin), "hierarchy: admin is also tenant-admin");
    }

    @Test
    void gdManagesOwnTenantOnlyAndNeverServiceConfig() {
        PortalAuthService.Session gd = s("GD", "mvno-a");
        assertDoesNotThrow(() -> authz.require(gd, ElisaAuthz.USERS_MANAGE, "mvno-a"));
        assertDoesNotThrow(() -> authz.require(gd, ElisaAuthz.TENANT_PROFILE_WRITE, "mvno-a"));
        WebApplicationException cross = assertThrows(WebApplicationException.class,
                () -> authz.require(gd, ElisaAuthz.USERS_MANAGE, "mvno-b"));
        assertEquals(403, cross.getResponse().getStatus());
        assertThrows(WebApplicationException.class,
                () -> authz.require(gd, ElisaAuthz.TENANT_CONFIG_WRITE, "mvno-a"));
        assertFalse(authz.isPlatformAdmin(gd));
    }

    @Test
    void legacyTenantLoginActsAsGd() {
        assertTrue(authz.isTenantAdmin(s("TENANT", "mvno-a")));
    }

    @Test
    void userOnlyReadsItself() {
        PortalAuthService.Session user = s("USER", "mvno-a");
        assertDoesNotThrow(() -> authz.require(user, ElisaAuthz.SELF_READ));
        assertThrows(WebApplicationException.class, () -> authz.require(user, ElisaAuthz.USERS_MANAGE, "mvno-a"));
        assertThrows(WebApplicationException.class, () -> authz.require(user, ElisaAuthz.CDR_READ, "mvno-a"));
    }

    @Test
    void unknownRoleAndNoSessionFailClosed() {
        assertThrows(WebApplicationException.class, () -> authz.require(s("WHATEVER", "mvno-a"), ElisaAuthz.SELF_READ));
        WebApplicationException none = assertThrows(WebApplicationException.class,
                () -> authz.require(null, ElisaAuthz.SELF_READ));
        assertEquals(401, none.getResponse().getStatus());
    }
}
