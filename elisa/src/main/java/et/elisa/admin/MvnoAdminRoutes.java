package et.elisa.admin;

import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCdr;
import et.elisa.persist.SfPortalUser;
import et.elisa.persist.SfTenant;
import et.elisa.tenant.MvnoService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * MVNO console: one page whose panels follow the caller's level — ADMIN (every tenant +
 * service config), GD (own tenant, its users, subscribers, CDR) and USER (own line + CDR).
 * Authorisation lives in {@link MvnoService} (jainslee-authz); this class only renders.
 */
@Path("/admin")
public class MvnoAdminRoutes {

    @Inject PortalAuthService auth;
    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject MvnoService mvno;

    @GET
    @Path("/mvno")
    @Produces(MediaType.TEXT_HTML)
    public Response page(@HeaderParam("Cookie") String cookie) {
        PortalAuthService.Session s = auth.resolve(cookie).orElse(null);
        if (s == null) {
            return Response.seeOther(URI.create("/admin/login")).build();
        }
        Map<String, String> vars = nav.vars(s, "/admin/mvno");
        String level = s.isAdmin() ? "ADMIN" : s.isGd() ? "GD" : s.isUser() ? "USER" : s.role();
        vars.put("{{LEVEL}}", AdminPageRenderer.esc(level));
        vars.put("{{TENANT}}", AdminPageRenderer.esc(s.tenantId() == null ? "—" : s.tenantId()));
        vars.put("{{SHOW_ADMIN}}", s.isAdmin() ? "" : "hidden");
        vars.put("{{SHOW_MANAGER}}", s.isAdmin() || s.isGd() ? "" : "hidden");
        return Response.ok(pages.page("mvno.html", vars)).build();
    }

    @GET
    @Path("/partial/mvno-tenants")
    @Produces(MediaType.TEXT_HTML)
    public String tenants(@HeaderParam("Cookie") String cookie) {
        PortalAuthService.Session s = session(cookie);
        List<SfTenant> list = mvno.tenants(s);
        if (list.isEmpty()) {
            return "<p class=\"hint\">No tenant.</p>";
        }
        StringBuilder sb = new StringBuilder("<table><thead><tr><th>tenant</th><th>name</th><th>enabled</th>"
                + "<th>max CPS</th><th>MSISDN prefix</th><th>ISC AS</th><th>description</th></tr></thead><tbody>");
        for (SfTenant t : list) {
            sb.append("<tr><td class=\"mono\">").append(e(t.id)).append("</td><td>").append(e(t.name))
                    .append("</td><td>").append(t.enabled ? "<span class=\"ok\">yes</span>" : "<span class=\"bad\">no</span>")
                    .append("</td><td class=\"mono\">").append(t.maxCps == 0 ? "∞" : String.valueOf(t.maxCps))
                    .append("</td><td class=\"mono\">").append(e(t.msisdnPrefix))
                    .append("</td><td class=\"mono\">").append(e(t.iscAsUri))
                    .append("</td><td>").append(e(t.description)).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @GET
    @Path("/partial/mvno-users")
    @Produces(MediaType.TEXT_HTML)
    public String users(@HeaderParam("Cookie") String cookie) {
        PortalAuthService.Session s = session(cookie);
        StringBuilder sb = new StringBuilder("<table><thead><tr><th>user</th><th>level</th><th>tenant</th>"
                + "<th>MSISDN</th><th>enabled</th></tr></thead><tbody>");
        for (SfPortalUser u : mvno.users(s)) {
            sb.append("<tr><td class=\"mono\">").append(e(u.username)).append("</td><td>").append(e(u.role))
                    .append("</td><td class=\"mono\">").append(e(u.tenantId)).append("</td><td class=\"mono\">")
                    .append(e(u.msisdn)).append("</td><td>").append(u.enabled).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @GET
    @Path("/partial/mvno-subscribers")
    @Produces(MediaType.TEXT_HTML)
    public String subscribers(@HeaderParam("Cookie") String cookie) {
        PortalAuthService.Session s = session(cookie);
        List<ImsSubscriber> list = mvno.subscribers(s);
        if (list.isEmpty()) {
            return "<p class=\"hint\">No subscriber in scope.</p>";
        }
        StringBuilder sb = new StringBuilder("<table><thead><tr><th>MSISDN</th><th>IMPI</th><th>IMPU</th>"
                + "<th>tenant</th><th>enabled</th></tr></thead><tbody>");
        for (ImsSubscriber sub : list) {
            sb.append("<tr><td class=\"mono\">").append(e(sub.msisdn)).append("</td><td class=\"mono\">")
                    .append(e(sub.impi)).append("</td><td class=\"mono\">").append(e(sub.impu))
                    .append("</td><td class=\"mono\">").append(e(sub.tenantId)).append("</td><td>")
                    .append(sub.enabled).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @GET
    @Path("/partial/mvno-cdr")
    @Produces(MediaType.TEXT_HTML)
    public String cdr(@HeaderParam("Cookie") String cookie) {
        PortalAuthService.Session s = session(cookie);
        List<SfCdr> list = mvno.cdrs(s, 25);
        if (list.isEmpty()) {
            return "<p class=\"hint\">No call record in scope.</p>";
        }
        StringBuilder sb = new StringBuilder("<table><thead><tr><th>start</th><th>from</th><th>to</th>"
                + "<th>MSISDN</th><th>status</th><th>sec</th></tr></thead><tbody>");
        for (SfCdr c : list) {
            sb.append("<tr><td class=\"mono\">").append(e(c.startTime == null ? "" : c.startTime.toString()))
                    .append("</td><td class=\"mono\">").append(e(c.sender)).append("</td><td class=\"mono\">")
                    .append(e(c.recipient)).append("</td><td class=\"mono\">").append(e(c.msisdn))
                    .append("</td><td>").append(e(c.status)).append("</td><td>")
                    .append(c.duration == null ? "" : c.duration).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @POST
    @Path("/mvno/tenants")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response saveTenant(@HeaderParam("Cookie") String cookie,
                               @FormParam("tenantId") String tenantId,
                               @FormParam("name") String name,
                               @FormParam("description") String description,
                               @FormParam("enabled") String enabled,
                               @FormParam("maxCps") String maxCps,
                               @FormParam("msisdnPrefix") String msisdnPrefix,
                               @FormParam("iscAsUri") String iscAsUri) {
        PortalAuthService.Session s = session(cookie);
        try {
            mvno.saveTenant(s, new MvnoService.TenantForm(tenantId, name, description,
                    "true".equalsIgnoreCase(enabled) || "on".equalsIgnoreCase(enabled),
                    parseInt(maxCps), msisdnPrefix, iscAsUri));
            return back("saved=" + enc(tenantId));
        } catch (WebApplicationException ex) {
            return back("error=" + enc(ex.getMessage()));
        }
    }

    @POST
    @Path("/mvno/users")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createUser(@HeaderParam("Cookie") String cookie,
                               @FormParam("username") String username,
                               @FormParam("password") String password,
                               @FormParam("role") String role,
                               @FormParam("tenantId") String tenantId,
                               @FormParam("msisdn") String msisdn,
                               @FormParam("displayName") String displayName) {
        PortalAuthService.Session s = session(cookie);
        try {
            mvno.createUser(s, username, password, role, tenantId, msisdn, displayName);
            return back("created=" + enc(username));
        } catch (WebApplicationException ex) {
            return back("error=" + enc(ex.getMessage()));
        }
    }

    private PortalAuthService.Session session(String cookie) {
        return auth.resolve(cookie).orElseThrow(() -> new WebApplicationException(Response.Status.UNAUTHORIZED));
    }

    private static Response back(String query) {
        return Response.seeOther(URI.create("/admin/mvno?" + query)).build();
    }

    private static int parseInt(String v) {
        try {
            return v == null || v.isBlank() ? 0 : Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            throw new WebApplicationException("max CPS must be a number", Response.Status.BAD_REQUEST);
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String e(String v) {
        return AdminPageRenderer.esc(v == null ? "" : v);
    }
}
