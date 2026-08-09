package et.elisa.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AdminNavRendererTest {

    private final AdminNavRenderer nav = new AdminNavRenderer();

    @Test
    void adminRailShowsUserProfilesAndCallSessionsUnderLive() {
        var session = new PortalAuthService.Session("t", "admin", PortalAuthService.ROLE_ADMIN, null, Instant.now().plusSeconds(60));
        String rail = nav.vars(session, "/admin/profiles").get("{{NAV_RAIL}}");
        assertTrue(rail.contains("User profiles"));
        assertTrue(rail.contains("href=\"/admin/profiles\""));
        assertTrue(rail.contains("Call sessions"));
        assertTrue(rail.contains("href=\"/admin/sessions\""));
        assertTrue(rail.contains("Management"));
        assertFalse(rail.contains(">Profiles</span>"), "old short label must not remain");
    }

    @Test
    void opsRailShowsLiveProfileAndSessionLinks() {
        var session = new PortalAuthService.Session("t", "ops1", PortalAuthService.ROLE_OPS, null, Instant.now().plusSeconds(60));
        String rail = nav.vars(session, "/admin").get("{{NAV_RAIL}}");
        assertTrue(rail.contains("/admin/profiles"));
        assertTrue(rail.contains("/admin/sessions"));
        assertTrue(rail.contains("Management"));
    }

    @Test
    void tenantRailShowsScopedProfilesAndSessions() {
        var session = new PortalAuthService.Session("t", "tenant1", PortalAuthService.ROLE_TENANT, "lab-1", Instant.now().plusSeconds(60));
        String rail = nav.vars(session, "/admin/portal").get("{{NAV_RAIL}}");
        assertTrue(rail.contains("Portal"));
        assertTrue(rail.contains("User profiles"));
        assertTrue(rail.contains("/admin/profiles"));
        assertTrue(rail.contains("Call sessions"));
        assertTrue(rail.contains("/admin/sessions"));
        assertFalse(rail.contains("Management"));
        assertFalse(rail.contains("/admin/sofia"));
    }

    @Test
    void unauthenticatedWithoutApiKeyHasNoOperatorSections() {
        Map<String, String> vars = nav.vars(null, "/admin", false);
        String rail = vars.get("{{NAV_RAIL}}");
        assertTrue(rail.contains("Live"));
        assertFalse(rail.contains("Management"));
    }
}
