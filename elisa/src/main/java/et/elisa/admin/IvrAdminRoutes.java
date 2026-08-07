package et.elisa.admin;

import et.elisa.ivr.IvrPublishService;
import et.elisa.ivr.IvrXmlBuilder;
import et.elisa.persist.SfIvrEntry;
import et.elisa.persist.SfIvrMenu;
import et.elisa.tenant.TenantService;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin")
public class IvrAdminRoutes {

    @Inject PortalAuthService auth;
    @Inject TenantService tenants;
    @Inject IvrPublishService publish;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @POST
    @Path("/ivr/menus")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createMenu(@FormParam("tenantId") String tenantId,
                               @FormParam("name") String name,
                               @FormParam("greeting") String greeting,
                               @FormParam("timeoutMs") Integer timeoutMs,
                               @FormParam("xmlOverride") String xmlOverride,
                               @HeaderParam("Cookie") String cookie,
                               @HeaderParam("X-SF-Admin-Key") String key,
                               @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireOps(cookie, key, keyQ);
        if (!tenants.canAccess(session, tenantId)) {
            return Response.status(403).build();
        }
        SfIvrMenu m = new SfIvrMenu();
        m.tenantId = tenantId.trim();
        m.name = name == null ? "main" : name.trim();
        m.greetingPrompt = greeting;
        m.timeoutMs = timeoutMs == null ? 5000 : timeoutMs;
        m.xmlOverride = xmlOverride;
        m.enabled = true;
        m.updatedAt = Instant.now();
        m.persist();
        return Response.seeOther(URI.create("/admin/ivr")).build();
    }

    @POST
    @Path("/ivr/entries")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createEntry(@FormParam("menuId") Long menuId,
                                @FormParam("digit") String digit,
                                @FormParam("action") String action,
                                @FormParam("destination") String destination,
                                @HeaderParam("Cookie") String cookie,
                                @HeaderParam("X-SF-Admin-Key") String key,
                                @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireOps(cookie, key, keyQ);
        SfIvrMenu menu = SfIvrMenu.findById(menuId);
        if (menu == null || !tenants.canAccess(session, menu.tenantId)) {
            return Response.status(403).build();
        }
        SfIvrEntry e = new SfIvrEntry();
        e.menuId = menuId;
        e.digit = digit == null ? "" : digit.trim();
        e.action = action == null ? "transfer" : action.trim();
        e.destination = destination;
        e.persist();
        return Response.seeOther(URI.create("/admin/ivr")).build();
    }

    /** T9 optional: export IVR XML under conf jail + reloadxml. */
    @POST
    @Path("/ivr/publish/{menuId}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response publishMenu(@PathParam("menuId") Long menuId,
                                @HeaderParam("Cookie") String cookie,
                                @HeaderParam("X-SF-Admin-Key") String key,
                                @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireOps(cookie, key, keyQ);
        SfIvrMenu menu = SfIvrMenu.findById(menuId);
        if (menu == null || !tenants.canAccess(session, menu.tenantId)) {
            return Response.status(403).build();
        }
        try {
            Map<String, Object> result = publish.publish(menu);
            return Response.ok(result).build();
        } catch (Exception e) {
            return Response.status(500).entity(Map.of(
                    "ok", false,
                    "message", e.getMessage() == null ? "publish failed" : e.getMessage())).build();
        }
    }

    @GET
    @Path("/partial/ivr-rows")
    @Produces(MediaType.TEXT_HTML)
    public String ivrRows(@HeaderParam("Cookie") String cookie,
                          @HeaderParam("X-SF-Admin-Key") String key,
                          @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        if (!keyOk && (session == null || !PortalAuthService.mayOperate(session, false))) {
            return "<p class=\"hint\">forbidden</p>";
        }
        List<SfIvrMenu> menus = SfIvrMenu.listAll();
        StringBuilder sb = new StringBuilder();
        for (SfIvrMenu m : menus) {
            if (session != null && !session.isAdmin() && !tenants.canAccess(session, m.tenantId) && !keyOk) {
                continue;
            }
            List<SfIvrEntry> entries = SfIvrEntry.list("menuId", m.id);
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(m.tenantId)).append("</td>")
                    .append("<td>").append(m.id).append("</td>")
                    .append("<td>").append(AdminPageRenderer.esc(m.name)).append("</td>")
                    .append("<td>").append(AdminPageRenderer.esc(m.greetingPrompt)).append("</td>")
                    .append("<td class=\"mono\">").append(entries.size()).append(" keys</td>")
                    .append("<td><button type=\"button\" class=\"btn\" hx-post=\"/admin/ivr/publish/")
                    .append(m.id)
                    .append("\" hx-swap=\"none\">Publish</button></td></tr>");
        }
        if (sb.isEmpty()) {
            return "<p class=\"hint\">No IVR menus — create one above.</p>";
        }
        return "<table><thead><tr><th>tenant</th><th>id</th><th>name</th><th>greeting</th><th>entries</th><th></th>"
                + "</tr></thead><tbody>" + sb + "</tbody></table>";
    }

    /** xml_curl style: return generated dialplan XML for a tenant menu (T9 primary). */
    @GET
    @Path("/ivr/xml/{tenantId}/{name}")
    @Produces(MediaType.APPLICATION_XML)
    public Response ivrXml(@PathParam("tenantId") String tenantId,
                           @PathParam("name") String name) {
        SfIvrMenu m = SfIvrMenu.find("tenantId = ?1 and name = ?2", tenantId, name).firstResult();
        if (m == null || !m.enabled) {
            return Response.status(404).entity("<!-- missing -->").build();
        }
        List<SfIvrEntry> entries = SfIvrEntry.list("menuId", m.id);
        return Response.ok(IvrXmlBuilder.build(m, entries)).build();
    }

    private PortalAuthService.Session requireOps(String cookie, String key, String keyQ) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        if (session != null && PortalAuthService.mayOperate(session, false)) {
            return session;
        }
        if (apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ))) {
            return new PortalAuthService.Session("", "api-key", PortalAuthService.ROLE_ADMIN, null, Instant.now());
        }
        throw new WebApplicationException(Response.Status.FORBIDDEN);
    }
}
