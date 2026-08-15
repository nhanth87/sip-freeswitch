package et.elisa.admin;

import et.elisa.cluster.ElisaInfinispanManager;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.DiameterRaBridge;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.hss.ImsIdentityKeys;
import et.elisa.ims.reg.BindingStore;
import et.elisa.ims.scscf.IscAsRouter;
import et.elisa.ims.trunk.SipTrunkService;
import et.elisa.persist.ImsBinding;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SipTrunkEntity;
import et.elisa.profile.ElisaProfileService;
import et.elisa.security.SipDigestHasher;
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
    @Inject ElisaProfileService sleeProfiles;
    @Inject SipTrunkService trunks;
    @Inject IscAsRouter isc;
    @Inject ElisaInfinispanManager ispn;
    @Inject et.elisa.esl.FreeSwitchRaBridge fsBridge;
    @Inject et.elisa.ims.charging.CgratesConfig cgratesConfig;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String nodeId;

    @GET
    @Path("/{page: ims|subscribers|bindings|trunks}")
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
        boolean cxConnected = diameter.peerConnected();
        String cxTruth;
        if (roles.hss()) {
            cxTruth = "local HSS-lite (Cx peer N/A) <span class=\"badge ok\">OK</span>";
        } else if (cxReady) {
            cxTruth = "true <span class=\"badge ok\">UP</span> (CER/CEA)";
        } else {
            cxTruth = "false <span class=\"badge mute\">DOWN</span>"
                    + (cxBound ? " bound+LISTEN≠ready" : " unbound")
                    + (cxConnected ? " tcp=up" : " tcp=down");
        }
        String ispnMode = ispn == null || !ispn.enabled() ? "off" : ispn.modeLabel();
        String ispnCluster = ispn == null ? "-" : ispn.clusterName();
        String resolvedIsc = "(n/a)";
        try {
            resolvedIsc = isc != null ? isc.asUri() : trunks.resolveIscOrMrfUri();
        } catch (RuntimeException ignored) {
            // ignore
        }
        boolean eslLive = fsBridge != null && fsBridge.live();
        String eslTruth = eslLive
                ? "true <span class=\"badge ok\">UP</span> (ESL heartbeat)"
                : "false <span class=\"badge mute\">DOWN</span> (LISTEN≠ready)";
        return "<table><tbody>"
                + row("nodeId", AdminPageRenderer.esc(nodeId))
                + row("roles", AdminPageRenderer.esc(roles.summary()))
                + row("features", AdminPageRenderer.esc(roles.featureSummary()))
                + row("realm", AdminPageRenderer.esc(roles.realm()))
                + row("pcscfUri", AdminPageRenderer.esc(roles.pcscfUri()))
                + row("icscfUri", AdminPageRenderer.esc(roles.icscfUri()))
                + row("scscfUri", AdminPageRenderer.esc(roles.scscfUri()))
                + row("iscAsUri", AdminPageRenderer.esc(roles.iscAsUri() == null || roles.iscAsUri().isBlank()
                        ? "(prop blank — trunk table/props)" : roles.iscAsUri()))
                + row("iscResolved", AdminPageRenderer.esc(resolvedIsc))
                + row("ispnMode", AdminPageRenderer.esc(ispnMode)
                        + (ispn != null && ispn.distSync()
                        ? " <span class=\"badge ok\">N–N</span>" : " <span class=\"badge mute\">lab</span>"))
                + row("ispnCluster", AdminPageRenderer.esc(ispnCluster))
                + row("cxRaBound", Boolean.toString(cxBound))
                + row("cxPeerConnected", Boolean.toString(cxConnected))
                + row("cxPeerReady", cxTruth)
                + row("eslLive", eslTruth)
                + row("hssLite", Boolean.toString(roles.hss())
                        + (roles.hss() ? "" : " — remote Cx via ra-diameter"))
                + row("chargingMaster", Boolean.toString(roles.featureCharging())
                        + (cgratesConfig == null ? ""
                        : " dualWrite=" + cgratesConfig.dualWrite()
                        + " rf=" + cgratesConfig.rfEnabled()
                        + " sessionS=" + cgratesConfig.sessionSEnabled()
                        + " ocs=" + cgratesConfig.ocsMode()))
                + "</tbody></table>";
    }

    @GET
    @Path("/partial/trunk-rows")
    @Produces(MediaType.TEXT_HTML)
    public String trunkRows(@HeaderParam("Cookie") String cookie,
                            @HeaderParam("X-SF-Admin-Key") String key,
                            @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        List<SipTrunkEntity> list = trunks.listAll();
        if (list.isEmpty()) {
            return "<p class=\"hint\">No trunks — Flyway seed or form below. Fallback props: "
                    + AdminPageRenderer.esc(trunks.fallbackUri()) + "</p>";
        }
        StringBuilder sb = new StringBuilder(
                "<table><thead><tr><th>id</th><th>kind</th><th>prio</th><th>peer</th>"
                        + "<th>sip_uri</th><th>enabled</th><th>tenant</th></tr></thead><tbody>");
        for (SipTrunkEntity t : list) {
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(t.id)).append("</td>")
                    .append("<td>").append(AdminPageRenderer.esc(t.kind)).append("</td>")
                    .append("<td>").append(t.priority).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(t.peerHost))
                    .append(':').append(t.peerPort).append('/').append(AdminPageRenderer.esc(t.transport))
                    .append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(t.sipUri)).append("</td>")
                    .append("<td>").append(t.enabled).append("</td>")
                    .append("<td class=\"mono\">")
                    .append(AdminPageRenderer.esc(t.tenantId == null ? "" : t.tenantId))
                    .append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @POST
    @Path("/trunks")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response upsertTrunk(@FormParam("id") String id,
                                @FormParam("name") String name,
                                @FormParam("peerHost") String peerHost,
                                @FormParam("peerPort") String peerPort,
                                @FormParam("transport") String transport,
                                @FormParam("sipUri") String sipUri,
                                @FormParam("kind") String kind,
                                @FormParam("priority") String priority,
                                @FormParam("tenantId") String tenantId,
                                @FormParam("enabled") String enabled,
                                @HeaderParam("Cookie") String cookie,
                                @HeaderParam("X-SF-Admin-Key") String key,
                                @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        int port = 5060;
        try {
            if (peerPort != null && !peerPort.isBlank()) {
                port = Integer.parseInt(peerPort.trim());
            }
        } catch (NumberFormatException ignored) {
            // default
        }
        int prio = 100;
        try {
            if (priority != null && !priority.isBlank()) {
                prio = Integer.parseInt(priority.trim());
            }
        } catch (NumberFormatException ignored) {
            // default
        }
        boolean on = enabled == null || "true".equalsIgnoreCase(enabled) || "on".equalsIgnoreCase(enabled);
        trunks.upsert(id, name, peerHost, port, transport, sipUri, on, tenantId, kind, prio);
        return Response.seeOther(URI.create("/admin/trunks")).build();
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
            return "<p class=\"hint\">No SIP users yet — create User A and User B above, then register two SIP clients.</p>";
        }
        java.util.HashSet<String> liveImpu = new java.util.HashSet<>();
        Instant now = Instant.now();
        for (ImsBinding b : bindings.listAll()) {
            if (b.impu != null && b.expiresAt != null && b.expiresAt.isAfter(now)) {
                liveImpu.add(b.impu);
            }
        }
        StringBuilder sb = new StringBuilder(
                "<table><thead><tr><th>SIP user</th><th>IMPU</th><th>digest</th><th>registered</th><th>enabled</th></tr></thead><tbody>");
        String realm = roles.realm();
        for (ImsSubscriber s : list) {
            String user = s.directoryUserId();
            boolean digest = s.fsA1Hash != null && !s.fsA1Hash.isBlank();
            boolean live = (s.impu != null && liveImpu.contains(s.impu))
                    || (user != null && liveImpu.contains(ImsIdentityKeys.impu(user, realm)));
            sb.append("<tr><td class=\"mono\">").append(AdminPageRenderer.esc(user)).append("</td>")
                    .append("<td class=\"mono\">").append(AdminPageRenderer.esc(s.impu)).append("</td>")
                    .append("<td>").append(digest ? "MD5" : "AKA").append("</td>")
                    .append("<td>").append(live ? "<span class=\"badge ok\">yes</span>" : "<span class=\"badge mute\">no</span>").append("</td>")
                    .append("<td>").append(s.enabled).append("</td></tr>");
        }
        return sb.append("</tbody></table>").toString();
    }

    @POST
    @Path("/subscribers")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createSubscriber(@FormParam("sipUser") String sipUser,
                                     @FormParam("sipPassword") String sipPassword,
                                     @FormParam("displayName") String displayName,
                                     @FormParam("msisdn") String msisdn,
                                     @FormParam("impi") String impi,
                                     @FormParam("impu") String impu,
                                     @FormParam("ki") String ki,
                                     @FormParam("opc") String opc,
                                     @FormParam("amf") String amf,
                                     @FormParam("ifcJson") String ifcJson,
                                     @HeaderParam("Cookie") String cookie,
                                     @HeaderParam("X-SF-Admin-Key") String key,
                                     @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        saveSipUser(sipUser, sipPassword, displayName, msisdn, impi, impu, ki, opc, amf, ifcJson);
        return Response.seeOther(URI.create("/admin/subscribers")).build();
    }

    @POST
    @Path("/subscribers/lab-pair")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createLabPair(@HeaderParam("Cookie") String cookie,
                                  @HeaderParam("X-SF-Admin-Key") String key,
                                  @QueryParam("key") String keyQ) {
        requireAdmin(cookie, key, keyQ);
        saveSipUser("alice", "alice", "User A", null, null, null, null, null, null, "{\"skip\":true}");
        saveSipUser("bob", "bob", "User B", null, null, null, null, null, null, "{\"skip\":true}");
        return Response.seeOther(URI.create("/admin/subscribers")).build();
    }

    private void saveSipUser(String sipUser, String sipPassword, String displayName, String msisdn,
                             String impi, String impu, String ki, String opc, String amf, String ifcJson) {
        String realm = roles.realm() != null ? roles.realm() : "ims.lab";
        String user = sipUser != null && !sipUser.isBlank()
                ? sipUser.trim()
                : ImsIdentityKeys.userPart(impu != null && !impu.isBlank() ? impu : impi);
        if (user == null || user.isBlank()) {
            throw new WebApplicationException("SIP user required", Response.Status.BAD_REQUEST);
        }
        ImsSubscriber s = new ImsSubscriber();
        s.impi = impi != null && !impi.isBlank() ? impi.trim() : ImsIdentityKeys.impi(user, realm);
        s.impu = impu != null && !impu.isBlank() ? impu.trim() : ImsIdentityKeys.impu(user, realm);
        s.displayName = displayName == null || displayName.isBlank() ? user : displayName.trim();
        s.msisdn = msisdn == null || msisdn.isBlank() ? null : msisdn.trim();
        s.ki = ki == null || ki.isBlank() ? "00112233445566778899aabbccddeeff" : ki.trim();
        s.opc = opc == null || opc.isBlank() ? "000102030405060708090a0b0c0d0e0f" : opc.trim();
        s.amf = amf == null || amf.isBlank() ? "8000" : amf.trim();
        s.ifcJson = ifcJson == null || ifcJson.isBlank() ? "{\"skip\":true}" : ifcJson;
        s.enabled = true;
        if (sipPassword != null && !sipPassword.isBlank()) {
            s.fsA1Hash = SipDigestHasher.a1Hash(user, realm, sipPassword);
        }
        ImsSubscriber saved = hss.upsert(s);
        if (sleeProfiles != null) {
            sleeProfiles.upsertSubscriber(saved);
        }
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
