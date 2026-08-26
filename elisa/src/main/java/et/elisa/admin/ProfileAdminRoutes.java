package et.elisa.admin;

import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.hss.ImsIdentityKeys;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCallSession;
import et.elisa.persist.SfTenant;
import et.elisa.profile.ElisaProfileService;
import et.elisa.security.SipDigestHasher;
import et.elisa.session.CallSessionService;
import et.elisa.cluster.CallSession;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin")
public class ProfileAdminRoutes {

    @Inject PortalAuthService auth;
    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject HssLiteService hss;
    @Inject CallSessionService callSessions;
    @Inject ElisaProfileService sleeProfiles;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @ConfigProperty(name = "sf.admin.dev-login", defaultValue = "true")
    boolean devLogin;

    /** Lab-only AKA defaults; forbidden when dev-login=false (W2-a). */
    private static final String LAB_KI = "00112233445566778899aabbccddeeff";
    private static final String LAB_OPC = "000102030405060708090a0b0c0d0e0f";

    @ConfigProperty(name = "sf.ims.realm", defaultValue = "ims.lab")
    String realm;

    @GET
    @Path("/{page: profiles|sessions}")
    @Produces(MediaType.TEXT_HTML)
    public Response pages(@PathParam("page") String page,
                          @HeaderParam("Cookie") String cookie,
                          @HeaderParam("X-SF-Admin-Key") String key,
                          @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireSession(cookie, key, keyQ);
        Map<String, String> vars = nav.vars(session, "/admin/" + page, apiKeyOk(key, keyQ));
        vars.put("{{TOKEN}}", "");
        return Response.ok(pages.page(page + ".html", vars)).build();
    }

    @GET
    @Path("/partial/profile-rows")
    @Produces(MediaType.TEXT_HTML)
    public String profileRows(@HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireSession(cookie, key, keyQ);
        List<ImsSubscriber> list = scopedProfiles(session);
        if (list.isEmpty()) {
            return "<p class=\"hint\">No profiles — provision MSISDN / IMPI / IMPU.</p>";
        }
        StringBuilder sb = new StringBuilder(
                "<table><thead><tr><th>MSISDN</th><th>IMPI</th><th>IMPU</th><th>tenant</th><th>enabled</th></tr></thead><tbody>");
        for (ImsSubscriber s : list) {
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(s.msisdn)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(s.impi)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(s.impu)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(s.tenantId)).append("</td>")
                    .append("<td>").append(s.enabled).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @POST
    @Path("/profiles")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createProfile(@FormParam("tenantId") String tenantId,
                                  @FormParam("sipUser") String sipUser,
                                  @FormParam("sipPassword") String sipPassword,
                                  @FormParam("msisdn") String msisdn,
                                  @FormParam("impi") String impi,
                                  @FormParam("impu") String impu,
                                  @FormParam("displayName") String displayName,
                                  @FormParam("ki") String ki,
                                  @FormParam("opc") String opc,
                                  @FormParam("amf") String amf,
                                  @FormParam("fsA1Hash") String fsA1Hash,
                                  @FormParam("ifcJson") String ifcJson,
                                  @HeaderParam("Cookie") String cookie,
                                  @HeaderParam("X-SF-Admin-Key") String key,
                                  @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireSession(cookie, key, keyQ);
        String user = sipUser != null && !sipUser.isBlank()
                ? sipUser.trim()
                : ImsIdentityKeys.userPart(impu != null && !impu.isBlank() ? impu : impi);
        String resolvedImpi = impi != null && !impi.isBlank()
                ? impi.trim()
                : (user == null ? null : ImsIdentityKeys.impi(user, realm));
        String resolvedImpu = impu != null && !impu.isBlank()
                ? impu.trim()
                : (user == null ? null : ImsIdentityKeys.impu(user, realm));
        if (resolvedImpi == null || resolvedImpu == null) {
            return Response.seeOther(URI.create("/admin/profiles?error=fields")).build();
        }
        String tid = resolveTenantId(session, tenantId);
        if (tid != null && SfTenant.findById(tid) == null && !apiKeyOk(key, keyQ)) {
            return Response.seeOther(URI.create("/admin/profiles?error=tenant")).build();
        }
        // W2-a hardening: no silent default Ki outside dev-login lab.
        String kiHex = blankToNull(ki);
        String opcHex = blankToNull(opc);
        if (kiHex == null || opcHex == null) {
            if (!devLogin) {
                return Response.seeOther(URI.create("/admin/profiles?error=keys")).build();
            }
            kiHex = LAB_KI;
            opcHex = LAB_OPC;
        }
        ImsSubscriber s = new ImsSubscriber();
        s.impi = resolvedImpi;
        s.impu = resolvedImpu;
        s.msisdn = blankToNull(msisdn);
        s.tenantId = blankToNull(tid);
        s.displayName = blankToNull(displayName);
        s.amf = amf == null || amf.isBlank() ? "8000" : amf.trim();
        if (sipPassword != null && !sipPassword.isBlank() && user != null) {
            s.fsA1Hash = SipDigestHasher.a1Hash(user, realm, sipPassword);
        } else {
            s.fsA1Hash = blankToNull(fsA1Hash);
        }
        s.ifcJson = ifcJson == null || ifcJson.isBlank() ? "{\"skip\":true}" : ifcJson;
        s.enabled = true;
        ImsSubscriber saved = hss.upsert(s, kiHex, opcHex);
        if (sleeProfiles != null) {
            sleeProfiles.upsertSubscriber(saved);
        }
        return Response.seeOther(URI.create("/admin/profiles")).build();
    }

    @GET
    @Path("/partial/session-rows")
    @Produces(MediaType.TEXT_HTML)
    public String sessionRows(@HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        PortalAuthService.Session session = requireSession(cookie, key, keyQ);
        String tenantFilter = tenantScope(session);
        List<SfCallSession> pg = callSessions.listLivePg(tenantFilter);
        if (session != null && session.isOps()) {
            var owned = SfTenant.<SfTenant>list("resellerId", session.username()).stream()
                    .map(t -> t.id).collect(Collectors.toSet());
            pg = pg.stream().filter(c -> c.tenantId != null && owned.contains(c.tenantId)).toList();
        }
        if (pg.isEmpty()) {
            List<CallSession> hot = callSessions.listLive();
            if (tenantFilter != null) {
                hot = hot.stream().filter(c -> tenantFilter.equals(c.tenantId())).toList();
            }
            if (hot.isEmpty()) {
                return "<p class=\"hint\">No live call sessions.</p>";
            }
            StringBuilder sb = new StringBuilder(
                    "<table><thead><tr><th>MSISDN</th><th>sip Call-ID</th><th>fs UUID</th><th>state</th><th>nodes</th></tr></thead><tbody>");
            for (CallSession c : hot) {
                sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(c.msisdn())).append("</td>")
                        .append("<td class=\"mono\">").append(AdminPageRenderer.esc(c.sipCallId())).append("</td>")
                        .append("<td class=\"mono\">").append(AdminPageRenderer.esc(c.fsUuid())).append("</td>")
                        .append("<td>").append(AdminPageRenderer.esc(c.state())).append("</td>")
                        .append("<td class=\"mono\">").append(AdminPageRenderer.esc(c.elisaNodeId()))
                        .append("→").append(AdminPageRenderer.esc(c.fsNodeId())).append("</td></tr>");
            }
            return sb.append("</tbody></table>").toString();
        }
        StringBuilder sb = new StringBuilder(
                "<table><thead><tr><th>MSISDN</th><th>sip Call-ID</th><th>fs UUID</th><th>state</th><th>tenant</th><th>started</th></tr></thead><tbody>");
        for (SfCallSession c : pg) {
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(c.msisdn)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(c.sipCallId)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(c.fsUuid)).append("</td>")
                    .append("<td>").append(AdminPageRenderer.esc(c.state)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(c.tenantId)).append("</td>")
                    .append("<td class=\"mono\">").append(c.startedAt).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    private List<ImsSubscriber> scopedProfiles(PortalAuthService.Session session) {
        List<ImsSubscriber> all = hss.listSubscribers();
        if (session == null || session.isAdmin()) {
            return all;
        }
        if (PortalAuthService.ROLE_TENANT.equals(session.role())) {
            String tid = session.tenantId();
            return all.stream().filter(s -> tid != null && tid.equals(s.tenantId)).collect(Collectors.toList());
        }
        if (PortalAuthService.ROLE_OPS.equals(session.role())) {
            var owned = SfTenant.<SfTenant>list("resellerId", session.username()).stream()
                    .map(t -> t.id).collect(Collectors.toSet());
            return all.stream().filter(s -> s.tenantId != null && owned.contains(s.tenantId))
                    .collect(Collectors.toList());
        }
        return List.of();
    }

    private String tenantScope(PortalAuthService.Session session) {
        if (session == null || session.isAdmin()) {
            return null;
        }
        if (PortalAuthService.ROLE_TENANT.equals(session.role())) {
            return session.tenantId();
        }
        return null; // OPS: filter in sessionRows via list then owned set
    }

    private String resolveTenantId(PortalAuthService.Session session, String formTenant) {
        if (session != null && PortalAuthService.ROLE_TENANT.equals(session.role())) {
            return session.tenantId();
        }
        return formTenant;
    }

    private PortalAuthService.Session requireSession(String cookie, String key, String keyQ) {
        if (apiKeyOk(key, keyQ)) {
            return auth.resolve(cookie).orElse(null);
        }
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        if (session == null) {
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        }
        if (session.isAdmin() || PortalAuthService.ROLE_OPS.equals(session.role())
                || PortalAuthService.ROLE_TENANT.equals(session.role())) {
            return session;
        }
        throw new WebApplicationException(Response.Status.FORBIDDEN);
    }

    private boolean apiKeyOk(String key, String keyQ) {
        return apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
