package et.elisa.admin;

import et.elisa.persist.*;
import et.elisa.tenant.PortalUserService;
import et.elisa.tenant.TenantService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin")
public class TenantUserAdminRoutes {

    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject PortalAuthService auth;
    @Inject TenantService tenants;
    @Inject PortalUserService portalUsers;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @POST
    @Path("/tenants")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response saveTenant(@FormParam("tenantId") String tenantId,
                               @FormParam("name") String name,
                               @FormParam("kind") String kind,
                               @FormParam("sourceAddrAlias") String sourceAddrAlias,
                               @FormParam("httpApiKey") String httpApiKey,
                               @FormParam("enabled") String enabled,
                               @FormParam("trunkTransport") String trunkTransport,
                               @FormParam("trunkConfig") String trunkConfig,
                               @HeaderParam("Cookie") String cookie,
                               @HeaderParam("X-SF-Admin-Key") String key,
                               @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireOperator(cookie, key, keyQ);
        try {
            SfTenant t = tenants.upsert(session, tenantId, name, kind, sourceAddrAlias, httpApiKey,
                    enabled == null || "true".equalsIgnoreCase(enabled) || "on".equalsIgnoreCase(enabled),
                    trunkTransport, trunkConfig);
            return Response.seeOther(URI.create("/admin/tenants?created=" + t.id)).build();
        } catch (WebApplicationException ex) {
            return Response.seeOther(URI.create("/admin/tenants?error=" + ex.getResponse().getStatus())).build();
        }
    }

    @GET
    @Path("/partial/tenant-rows")
    @Produces(MediaType.TEXT_HTML)
    public String tenantRows(@HeaderParam("Cookie") String cookie,
                             @HeaderParam("X-SF-Admin-Key") String key,
                             @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = resolve(cookie, key, keyQ);
        if (session == null || !PortalAuthService.mayOperate(session, apiKeyOk(key, keyQ))) {
            return "<p class=\"hint\">forbidden</p>";
        }
        List<SfTenant> list = tenants.listFor(session);
        if (list.isEmpty()) {
            return "<p class=\"hint\">No tenants yet — create one above.</p>";
        }
        StringBuilder body = new StringBuilder();
        for (SfTenant t : list) {
            body.append("<tr>")
                    .append("<td class=\"mono\">").append(esc(t.id)).append("</td>")
                    .append("<td>").append(esc(t.name)).append("</td>")
                    .append("<td>").append(esc(t.kind)).append("</td>")
                    .append("<td>").append(esc(t.trunkTransport)).append("</td>")
                    .append("<td>").append(t.enabled).append("</td>")
                    .append("<td class=\"mono\">").append(esc(t.resellerId)).append("</td>")
                    .append("<td class=\"mono\">").append(esc(t.sourceAddrAlias)).append("</td>")
                    .append("<td class=\"mono\">…").append(esc(t.httpApiKeyFp)).append("</td>")
                    .append("</tr>");
        }
        return "<table><thead><tr><th>id</th><th>name</th><th>kind</th><th>transport</th>"
                + "<th>enabled</th><th>reseller</th><th>OA</th><th>key fp</th></tr></thead><tbody>"
                + body + "</tbody></table>";
    }

    @POST
    @Path("/users")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createUser(@FormParam("username") String username,
                               @FormParam("password") String password,
                               @FormParam("role") String role,
                               @FormParam("tenantId") String tenantId,
                               @FormParam("displayName") String displayName,
                               @HeaderParam("Cookie") String cookie,
                               @HeaderParam("X-SF-Admin-Key") String key,
                               @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = resolve(cookie, key, keyQ);
        boolean keyOk = apiKeyOk(key, keyQ);
        if (session == null || !session.isAdmin()) {
            if (!keyOk) {
                return Response.status(403).build();
            }
            session = new PortalAuthService.Session("", "api-key", PortalAuthService.ROLE_ADMIN, null, Instant.now());
        }
        try {
            SfPortalUser u = portalUsers.create(session, username, password, role, tenantId, displayName);
            return Response.seeOther(URI.create("/admin/users?created=" + u.username)).build();
        } catch (WebApplicationException ex) {
            return Response.seeOther(URI.create("/admin/users?error=" + ex.getResponse().getStatus())).build();
        }
    }

    @GET
    @Path("/partial/user-rows")
    @Produces(MediaType.TEXT_HTML)
    public String userRows(@HeaderParam("Cookie") String cookie,
                           @HeaderParam("X-SF-Admin-Key") String key,
                           @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = resolve(cookie, key, keyQ);
        if ((session == null || !session.isAdmin()) && !apiKeyOk(key, keyQ)) {
            return "<p class=\"hint\">ADMIN only</p>";
        }
        List<SfPortalUser> list = portalUsers.listAll();
        if (list.isEmpty()) {
            return "<p class=\"hint\">No portal users</p>";
        }
        StringBuilder body = new StringBuilder();
        for (SfPortalUser u : list) {
            body.append("<tr><td class=\"mono\">").append(esc(u.username)).append("</td>")
                    .append("<td>").append(esc(u.role)).append("</td>")
                    .append("<td class=\"mono\">").append(esc(u.tenantId)).append("</td>")
                    .append("<td>").append(u.enabled).append("</td>")
                    .append("<td class=\"mono\">…").append(esc(u.httpApiKeyFp)).append("</td></tr>");
        }
        return "<table><thead><tr><th>user</th><th>role</th><th>tenant</th><th>enabled</th><th>key fp</th>"
                + "</tr></thead><tbody>" + body + "</tbody></table>";
    }

    private PortalAuthService.Session requireOperator(String cookie, String key, String keyQ) {
        PortalAuthService.Session session = resolve(cookie, key, keyQ);
        if (session != null && PortalAuthService.mayOperate(session, false)) {
            return session;
        }
        if (apiKeyOk(key, keyQ)) {
            return new PortalAuthService.Session("", "api-key", PortalAuthService.ROLE_ADMIN, null, Instant.now());
        }
        throw new WebApplicationException(Response.Status.FORBIDDEN);
    }

    private PortalAuthService.Session resolve(String cookie, String key, String keyQ) {
        return auth.resolve(cookie).orElse(null);
    }

    private boolean apiKeyOk(String key, String keyQ) {
        return apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
    }

    private static String esc(String s) {
        return AdminPageRenderer.esc(s == null ? "" : s);
    }
}
