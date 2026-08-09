package et.elisa.admin;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.DiameterRaBridge;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.persist.ImsBinding;
import et.elisa.persist.ImsSubscriber;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin")
public class ImsAdminRoutes {

    @Inject PortalAuthService auth;
    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject ImsRoleFlags roles;
    @Inject DiameterRaBridge diameter;
    @Inject HssLiteService hss;
    @Inject BindingStore bindings;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @GET
    @Path("/{page: ims|subscribers|bindings}")
    @Produces(MediaType.TEXT_HTML)
    public Response pages(@PathParam("page") String page,
                          @HeaderParam("Cookie") String cookie,
                          @HeaderParam("X-SF-Admin-Key") String key,
                          @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        Map<String, String> vars = nav.vars(auth.resolve(cookie).orElse(null), "/admin/" + page, true);
        vars.put("{{TOKEN}}", "");
        return Response.ok(pages.page(page + ".html", vars)).build();
    }

    @GET
    @Path("/partial/ims-status")
    @Produces(MediaType.TEXT_HTML)
    public String imsStatus(@HeaderParam("Cookie") String cookie,
                            @HeaderParam("X-SF-Admin-Key") String key,
                            @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        boolean cxReady = diameter.peerReady();
        boolean cxBound = diameter.bound();
        String cxTruth = roles.hss()
                ? "local HSS-lite (Cx peer N/A) <span class=\"badge ok\">OK</span>"
                : Boolean.toString(cxReady)
                        + (cxReady ? " <span class=\"badge ok\">UP</span>" : " <span class=\"badge mute\">DOWN</span>");
        return "<table><tbody>"
                + row("roles", AdminPageRenderer.esc(roles.summary()))
                + row("realm", AdminPageRenderer.esc(roles.realm()))
                + row("pcscfUri", AdminPageRenderer.esc(roles.pcscfUri()))
                + row("icscfUri", AdminPageRenderer.esc(roles.icscfUri()))
                + row("scscfUri", AdminPageRenderer.esc(roles.scscfUri()))
                + row("iscAsUri", AdminPageRenderer.esc(roles.iscAsUri() == null || roles.iscAsUri().isBlank()
                        ? "(trunk fallback)" : roles.iscAsUri()))
                + row("cxRaBound", Boolean.toString(cxBound))
                + row("cxPeerReady", cxTruth)
                + row("hssLite", Boolean.toString(roles.hss()))
                + "</tbody></table>";
    }

    @GET
    @Path("/partial/subscriber-rows")
    @Produces(MediaType.TEXT_HTML)
    public String subscriberRows(@HeaderParam("Cookie") String cookie,
                                 @HeaderParam("X-SF-Admin-Key") String key,
                                 @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        List<ImsSubscriber> list = hss.listSubscribers();
        if (list.isEmpty()) {
            return "<p class=\"hint\">No IMS subscribers — provision IMPI/IMPU/Ki/OPc.</p>";
        }
        StringBuilder sb = new StringBuilder("<table><thead><tr><th>IMPI</th><th>IMPU</th><th>SQN</th><th>enabled</th></tr></thead><tbody>");
        for (ImsSubscriber s : list) {
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(s.impi)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(s.impu)).append("</td>")
                    .append("<td>").append(s.sqn).append("</td>")
                    .append("<td>").append(s.enabled).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @POST
    @Path("/subscribers")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createSubscriber(@FormParam("impi") String impi,
                                     @FormParam("impu") String impu,
                                     @FormParam("ki") String ki,
                                     @FormParam("opc") String opc,
                                     @FormParam("amf") String amf,
                                     @FormParam("ifcJson") String ifcJson,
                                     @HeaderParam("Cookie") String cookie,
                                     @HeaderParam("X-SF-Admin-Key") String key,
                                     @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        ImsSubscriber s = new ImsSubscriber();
        s.impi = impi.trim();
        s.impu = impu.trim();
        s.ki = ki == null || ki.isBlank() ? "00112233445566778899aabbccddeeff" : ki.trim();
        s.opc = opc == null || opc.isBlank() ? "000102030405060708090a0b0c0d0e0f" : opc.trim();
        s.amf = amf == null || amf.isBlank() ? "8000" : amf.trim();
        s.ifcJson = ifcJson;
        s.enabled = true;
        s.createdAt = Instant.now();
        s.updatedAt = Instant.now();
        s.persist();
        return Response.seeOther(URI.create("/admin/subscribers")).build();
    }

    @GET
    @Path("/partial/binding-rows")
    @Produces(MediaType.TEXT_HTML)
    public String bindingRows(@HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        List<ImsBinding> list = bindings.listAll();
        if (list.isEmpty()) {
            return "<p class=\"hint\">No registration bindings.</p>";
        }
        StringBuilder sb = new StringBuilder(
                "<table><thead><tr><th>IMPU</th><th>Contact</th><th>home S-CSCF</th><th>expires</th></tr></thead><tbody>");
        for (ImsBinding b : list) {
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(b.impu)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(b.contact)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(b.homeScscfNode)).append("</td>")
                    .append("<td class=\"mono\">").append(b.expiresAt).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    private void requireAdmin(String cookie, String key, String keyQ) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        if (session != null && session.isAdmin()) {
            return;
        }
        if (apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ))) {
            return;
        }
        throw new WebApplicationException(Response.Status.FORBIDDEN);
    }

    private static String row(String k, String v) {
        return "<tr><th class=\"mono\">" + k + "</th><td>" + v + "</td></tr>";
    }
}
