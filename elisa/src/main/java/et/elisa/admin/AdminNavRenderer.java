package et.elisa.admin;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class AdminNavRenderer {

    private record Link(String label, String href, String ico) {}

    private static final Link[] LIVE = {
            new Link("Dashboard", "/admin", "·"),
            new Link("Calls", "/admin/calls", "·"),
            new Link("CDR", "/admin/cdr", "·")
    };
    private static final Link[] MANAGEMENT = {
            new Link("Sofia", "/admin/sofia", "·"),
            new Link("Gateways", "/admin/gateways", "·"),
            new Link("Directory", "/admin/directory", "·"),
            new Link("Dialplan", "/admin/dialplan", "·"),
            new Link("ACL", "/admin/acl", "·"),
            new Link("Conferences", "/admin/conferences", "·"),
            new Link("IVR", "/admin/ivr", "·"),
            new Link("Callcenter", "/admin/callcenter", "·"),
            new Link("Recordings", "/admin/recordings", "·"),
            new Link("FS Control", "/admin/fs-control", "·")
    };
    private static final Link[] ADMIN = {
            new Link("IMS", "/admin/ims", "·"),
            new Link("Subscribers", "/admin/subscribers", "·"),
            new Link("Bindings", "/admin/bindings", "·"),
            new Link("Tenants", "/admin/tenants", "·"),
            new Link("Users", "/admin/users", "·"),
            new Link("ESL", "/admin/esl", "·"),
            new Link("System", "/admin/system", "·")
    };
    private static final Link[] NETWORK = {
            new Link("ICE/TURN", "/admin/ice", "·")
    };
    private static final Link[] PORTAL = {
            new Link("Portal", "/admin/portal", "·")
    };

    public Map<String, String> vars(PortalAuthService.Session session, String activePath) {
        return vars(session, activePath, false);
    }

    public Map<String, String> vars(PortalAuthService.Session session, String activePath, boolean apiKeyOperator) {
        Map<String, String> vars = new LinkedHashMap<>();
        String role = session == null ? (apiKeyOperator ? PortalAuthService.ROLE_ADMIN : "") : session.role();
        boolean operator = apiKeyOperator
                || PortalAuthService.ROLE_ADMIN.equals(role)
                || PortalAuthService.ROLE_OPS.equals(role);
        boolean admin = apiKeyOperator || PortalAuthService.ROLE_ADMIN.equals(role);
        boolean tenant = PortalAuthService.ROLE_TENANT.equals(role);

        vars.put("{{NAV_RAIL}}", railHtml(role, operator, admin, tenant, activePath));
        vars.put("{{AUTH_NAV}}", session == null
                ? "<a class=\"btn btn-ghost\" href=\"/admin/login\">Login</a>"
                : "<span class=\"hint mono\">" + AdminPageRenderer.esc(session.username()) + " · "
                        + AdminPageRenderer.esc(role) + "</span> "
                        + "<a class=\"btn btn-ghost\" href=\"/admin/logout\">Logout</a>");
        vars.put("{{ROLE}}", AdminPageRenderer.esc(role));
        vars.put("{{ACTIVE_PATH}}", AdminPageRenderer.esc(activePath == null ? "" : activePath));
        // legacy token used by older stubs
        vars.put("{{NAV_LINKS}}", "");
        return vars;
    }

    /** @deprecated prefer vars(session, activePath) */
    public Map<String, String> vars(PortalAuthService.Session session) {
        return vars(session, "/admin");
    }

    private static String railHtml(String role, boolean operator, boolean admin, boolean tenant, String active) {
        StringBuilder sb = new StringBuilder();
        sb.append("<aside class=\"sf-rail\" aria-label=\"ELISA navigation\">");
        sb.append("<div class=\"sf-rail-brand\"><span class=\"mark\" aria-hidden=\"true\"></span>");
        sb.append("<a class=\"brand\" href=\"").append(tenant ? "/admin/portal" : "/admin").append("\">ELISA</a></div>");
        sb.append("<nav class=\"sf-rail-nav\">");
        if (tenant) {
            group(sb, "Portal", PORTAL, active);
        } else {
            group(sb, "Live", LIVE, active);
            if (operator) {
                group(sb, "Management", MANAGEMENT, active);
                group(sb, "Admin", filterAdmin(admin), active);
                group(sb, "Network", NETWORK, active);
            }
        }
        sb.append("</nav>");
        sb.append("<div class=\"sf-rail-foot\">");
        sb.append("<button type=\"button\" class=\"theme-toggle\" @click=\"$store.sf.toggleTheme()\">");
        sb.append("<span class=\"theme-toggle-label\">Theme</span></button>");
        sb.append("<button type=\"button\" class=\"btn btn-ghost\" @click=\"toggleRail()\" title=\"Collapse rail\">⇄</button>");
        sb.append("</div></aside>");
        return sb.toString();
    }

    private static Link[] filterAdmin(boolean isAdmin) {
        if (isAdmin) {
            return ADMIN;
        }
        return new Link[] {
                new Link("Tenants", "/admin/tenants", "·"),
                new Link("ESL", "/admin/esl", "·"),
                new Link("System", "/admin/system", "·")
        };
    }

    private static void group(StringBuilder sb, String label, Link[] links, String active) {
        sb.append("<div class=\"sf-rail-group\"><span class=\"sf-rail-group-label\">")
                .append(AdminPageRenderer.esc(label)).append("</span>");
        for (Link link : links) {
            boolean on = pathActive(active, link.href());
            sb.append("<a class=\"sf-rail-link").append(on ? " active" : "").append("\" href=\"")
                    .append(link.href()).append("\"><span class=\"sf-rail-ico\" aria-hidden=\"true\">")
                    .append(link.ico()).append("</span><span>")
                    .append(AdminPageRenderer.esc(link.label())).append("</span></a>");
        }
        sb.append("</div>");
    }

    private static boolean pathActive(String active, String href) {
        if (active == null || active.isBlank()) {
            return false;
        }
        if ("/admin".equals(href)) {
            return "/admin".equals(active) || active.endsWith("/admin/index.html");
        }
        return active.equals(href) || active.startsWith(href + "/");
    }
}
