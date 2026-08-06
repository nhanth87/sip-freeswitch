package et.elisa.admin;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class AdminNavRenderer {
    private static final String[] PAGES = {"Dashboard|/admin", "Sofia|/admin/sofia", "Gateways|/admin/gateways",
            "Directory|/admin/directory", "Dialplan|/admin/dialplan", "ACL|/admin/acl", "Calls|/admin/calls",
            "CDR|/admin/cdr", "System|/admin/system", "Conferences|/admin/conferences", "IVR|/admin/ivr",
            "Callcenter|/admin/callcenter", "Recordings|/admin/recordings", "ESL|/admin/esl", "ICE/TURN|/admin/codecs",
            "Tenants|/admin/tenants", "Users|/admin/users"};
    public Map<String, String> vars(PortalAuthService.Session session) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("{{NAV_LINKS}}", nav());
        vars.put("{{AUTH_NAV}}", session == null ? "<a href=\"/admin/login\">Login</a>" : "<a href=\"/admin/logout\">Logout</a>");
        return vars;
    }
    private static String nav() {
        StringBuilder nav = new StringBuilder();
        for (String page : PAGES) { String[] p = page.split("\\|"); nav.append("<a href=\"").append(p[1]).append("\">").append(p[0]).append("</a> "); }
        return nav.toString();
    }
}
