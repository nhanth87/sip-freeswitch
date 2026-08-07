package et.elisa.admin;

import et.elisa.acl.SipIngressAclService;
import et.elisa.esl.EslClient;
import et.elisa.esl.FsLinkStatusService;
import et.elisa.metrics.SipCpsMetrics;
import et.elisa.persist.*;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin")
public class AdminRoutes {

    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject PortalAuthService auth;
    @Inject SipCpsMetrics metrics;
    @Inject FsLinkStatusService fs;
    @Inject EslClient esl;
    @Inject SipIngressAclService sipAcl;
    @Inject et.elisa.media.MediaIceTurnPolicy mediaPolicy;
    @Inject et.elisa.tenant.TenantService tenantService;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @ConfigProperty(name = "sip.trunk.domain", defaultValue = "freeswitch.lab")
    String sipRealm;

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response dashboard(@HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        return page("index.html", cookie, key, keyQ, true, "/admin");
    }

    @GET
    @Path("/portal")
    @Produces(MediaType.TEXT_HTML)
    public Response portal(@HeaderParam("Cookie") String cookie,
                           @HeaderParam("X-SF-Admin-Key") String key,
                           @QueryParam("key") String keyQ) {
        return page("portal.html", cookie, key, keyQ, true, "/admin/portal");
    }

    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage(@HeaderParam("Cookie") String cookie) {
        return page("login.html", cookie, null, null, false, "/admin/login");
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response login(@FormParam("username") String username, @FormParam("password") String password) {
        return auth.login(username, password)
                .<Response>map(s -> {
                    String dest = "TENANT".equalsIgnoreCase(s.role()) ? "/admin/portal" : "/admin";
                    return Response.seeOther(java.net.URI.create(dest))
                            .header("Set-Cookie", PortalAuthService.setCookieHeader(s.token()))
                            .build();
                })
                .orElseGet(() -> Response.seeOther(java.net.URI.create("/admin/login?error=invalid")).build());
    }

    @GET
    @Path("/logout")
    public Response logout() {
        return Response.seeOther(java.net.URI.create("/admin/login"))
                .header("Set-Cookie", PortalAuthService.clearCookieHeader())
                .build();
    }

    @GET
    @Path("/{page: sofia|gateways|directory|dialplan|acl|calls|cdr|system|conferences|ivr|callcenter|recordings|esl|codecs|ice|tenants|users}")
    @Produces(MediaType.TEXT_HTML)
    public Response namedPage(@PathParam("page") String page,
                              @HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ,
                              @HeaderParam("HX-Request") String hx) {
        if ("codecs".equals(page)) {
            return Response.seeOther(java.net.URI.create("/admin/ice")).build();
        }
        if (isRestrictedAdminPage(page) && !isOperator(cookie, key, keyQ)) {
            if ("true".equalsIgnoreCase(hx)) {
                return Response.status(401).header("HX-Redirect", "/admin/login").build();
            }
            return Response.seeOther(java.net.URI.create("/admin/login")).build();
        }
        if (isAdminOnlyPage(page) && !isAdmin(cookie, key, keyQ)) {
            return Response.status(403).entity("ADMIN only").build();
        }
        String template = "ice".equals(page) ? "ice.html" : page + ".html";
        return page(template, cookie, key, keyQ, true, "/admin/" + ("ice".equals(page) ? "ice" : page));
    }

    @GET
    @Path("/static/{asset}")
    public Response staticAsset(@PathParam("asset") String asset) {
        byte[] body = pages.staticAsset(asset);
        if (body == null) {
            return Response.status(404).build();
        }
        String type = asset.endsWith(".css") ? "text/css"
                : asset.endsWith(".js") ? "application/javascript" : "application/octet-stream";
        return Response.ok(body, type).build();
    }

    @GET
    @Path("/rates")
    @Produces(MediaType.TEXT_HTML)
    public String rates() {
        Map<String, Object> st = fs.status();
        boolean live = Boolean.TRUE.equals(st.get("eslLive"))
                || "true".equalsIgnoreCase(String.valueOf(st.get("eslLive")));
        return pages.partial("rates.html", Map.of(
                "{{INVITES}}", Long.toString(metrics.inviteCount()),
                "{{REJECTS}}", Long.toString(metrics.rejectCount()),
                "{{DIALOGS}}", Long.toString(metrics.activeDialogs()),
                "{{ESL_BADGE_CLASS}}", live ? "badge-live" : "badge-down",
                "{{ESL_LABEL}}", live ? "LIVE" : "DOWN",
                "{{CHANNELS}}", String.valueOf(st.getOrDefault("channels", 0))));
    }

    @GET
    @Path("/links/fs")
    @Produces(MediaType.TEXT_HTML)
    public String linksFsHtml() {
        Map<String, Object> st = fs.status();
        boolean live = Boolean.TRUE.equals(st.get("eslLive"))
                || "true".equalsIgnoreCase(String.valueOf(st.get("eslLive")));
        return pages.partial("fs-status.html", Map.of(
                "{{ESL_BADGE_CLASS}}", live ? "badge-live" : "badge-down",
                "{{ESL_LABEL}}", live ? "LIVE" : "DOWN",
                "{{CHANNELS}}", String.valueOf(st.getOrDefault("channels", 0))));
    }

    @GET
    @Path("/partial/{name: fs-status|calls-rows|cdr-rows|gateway-rows|extension-rows|dialplan-rows|acl-rows|conference-rows|recording-rows|queue-rows}")
    @Produces(MediaType.TEXT_HTML)
    public String partial(@PathParam("name") String name,
                          @HeaderParam("Cookie") String cookie,
                          @HeaderParam("X-SF-Admin-Key") String key,
                          @QueryParam("key") String keyQ) {
        return switch (name) {
            case "fs-status" -> linksFsHtml();
            case "gateway-rows" -> rows(SfGateway.<SfGateway>listAll().stream()
                    .map(g -> "<tr><td>" + esc(g.name) + "</td><td>" + esc(g.profile) + "</td><td>"
                            + g.enabled + "</td></tr>")
                    .collect(Collectors.joining()), "name|profile|enabled");
            case "extension-rows" -> rows(SfExtension.<SfExtension>listAll().stream()
                    .map(e -> "<tr><td class=\"mono\">" + esc(e.tenantId) + "</td><td>" + esc(e.extension)
                            + "</td><td>" + e.enabled + "</td></tr>")
                    .collect(Collectors.joining()), "tenant|extension|enabled");
            case "dialplan-rows" -> rows(SfDialplanRule.<SfDialplanRule>listAll().stream()
                    .map(r -> "<tr><td>" + esc(r.name) + "</td><td>" + esc(r.destination) + "</td><td>"
                            + esc(r.action) + "</td><td>" + r.priority + "</td></tr>")
                    .collect(Collectors.joining()), "name|destination|action|priority");
            case "acl-rows" -> {
                String body = SfAclEntry.<SfAclEntry>list("listName", SipIngressAclService.LIST_SIP_INGRESS).stream()
                        .map(a -> "<tr><td>" + a.id + "</td><td>" + esc(a.cidr) + "</td><td>"
                                + esc(a.action) + "</td><td>" + a.enabled
                                + "</td><td><form method=\"post\" action=\"/admin/acl/delete\" style=\"display:inline\">"
                                + "<input type=\"hidden\" name=\"id\" value=\"" + a.id + "\"/>"
                                + "<button type=\"submit\">Remove</button></form></td></tr>")
                        .collect(Collectors.joining());
                String policy = "ACL enabled=" + sipAcl.isEnabled()
                        + " default=" + sipAcl.defaultAction()
                        + " loadedRules=" + sipAcl.ruleCount()
                        + " — only <b>allow</b> CIDRs may send SIP; others get 403.";
                if (body.isBlank()) {
                    yield "<p class=\"hint\">" + policy + "</p><p class=\"mono\">No sip-ingress rules — "
                            + ("deny".equalsIgnoreCase(sipAcl.defaultAction())
                            ? "all SIP peers blocked until you approve an IP."
                            : "default allow.")
                            + "</p>";
                }
                yield "<p class=\"hint\">" + policy + "</p>"
                        + rows(body, "id|cidr|action|enabled|");
            }
            case "cdr-rows" -> cdrRowsHtml(cookie, key, keyQ);
            case "conference-rows" -> rows(SfConferenceProfile.<SfConferenceProfile>listAll().stream()
                    .map(c -> "<tr><td>" + esc(c.name) + "</td></tr>")
                    .collect(Collectors.joining()), "name");
            case "recording-rows" -> rows(SfRecording.<SfRecording>listAll().stream()
                    .map(r -> "<tr><td>" + esc(r.callUuid) + "</td><td>" + esc(r.path) + "</td></tr>")
                    .collect(Collectors.joining()), "uuid|path");
            case "queue-rows" -> rows(SfQueue.<SfQueue>listAll().stream()
                    .map(q -> "<tr><td>" + esc(q.name) + "</td><td>" + esc(q.strategy) + "</td></tr>")
                    .collect(Collectors.joining()), "name|strategy");
            case "calls-rows" -> {
                String raw = esl.api("show channels as delim |");
                var channels = CallChannelParser.parse(raw);
                if (channels.isEmpty()) {
                    yield "<p class=\"hint\">" + AdminPageRenderer.esc(
                            raw.isBlank() || raw.startsWith("-ERR")
                                    ? "No active channels / ESL down"
                                    : "No channels") + "</p>";
                }
                StringBuilder rows = new StringBuilder();
                for (var c : channels) {
                    rows.append("<tr><td class=\"mono\">").append(esc(c.uuid())).append("</td>")
                            .append("<td>").append(esc(c.caller())).append("</td>")
                            .append("<td>").append(esc(c.callee())).append("</td>")
                            .append("<td>").append(esc(c.state())).append("</td>")
                            .append("<td>").append(esc(c.callstate())).append("</td>")
                            .append("<td class=\"mono\">").append(esc(c.created())).append("</td>")
                            .append("<td class=\"hangup-cell\" data-uuid=\"").append(esc(c.uuid()))
                            .append("\"></td></tr>");
                }
                yield pages.partial("calls-rows.html", Map.of(
                        "{{ROWS}}", rows.toString(),
                        "{{EMPTY}}", ""));
            }
            default -> pages.partial(name + ".html", Map.of("{{EMPTY}}", "No records"));
        };
    }

    private String cdrRowsHtml(String cookie, String key, String keyQ) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        List<SfCdr> list;
        if (keyOk || (session != null && session.isAdmin())) {
            list = SfCdr.<SfCdr>listAll();
        } else if (session != null && session.isOps()) {
            list = SfCdr.list("resellerId", session.username());
        } else if (session != null && session.tenantId() != null) {
            list = SfCdr.list("tenantId", session.tenantId());
        } else {
            return "<p class=\"hint\">Sign in to view CDR</p>";
        }
        String body = list.stream().limit(100)
                .map(c -> "<tr><td class=\"mono\">" + esc(String.valueOf(c.startTime != null ? c.startTime : c.createdAt))
                        + "</td><td class=\"mono\">" + esc(c.callUuid) + "</td><td>" + esc(c.sender)
                        + "</td><td>" + esc(c.recipient) + "</td><td>" + esc(String.valueOf(c.duration))
                        + "</td><td>" + esc(c.hangupCause != null ? c.hangupCause : c.status)
                        + "</td><td class=\"mono\">" + esc(c.tenantId) + "</td></tr>")
                .collect(Collectors.joining());
        if (body.isBlank()) {
            return "<p class=\"hint\">No CDR rows</p>";
        }
        return rows(body, "time|uuid|caller|callee|duration|hangup|tenant");
    }

    @GET
    @Path("/media/policy")
    @Produces(MediaType.TEXT_HTML)
    public String mediaPolicyPartial() {
        return """
                <dl class="metrics">
                  <dt>rtp_redirect (firewall)</dt><dd>%s</dd>
                  <dt>ICE required</dt><dd>%s</dd>
                  <dt>TURN relay required</dt><dd>%s</dd>
                  <dt>STUN</dt><dd class="mono">%s</dd>
                  <dt>TURN</dt><dd class="mono">%s (user=%s)</dd>
                </dl>
                """.formatted(
                mediaPolicy.rtpRedirect(),
                mediaPolicy.iceRequired() || mediaPolicy.rtpRedirect(),
                mediaPolicy.requireTurn(),
                AdminPageRenderer.esc(mediaPolicy.stunUri()),
                AdminPageRenderer.esc(mediaPolicy.turnUri()),
                AdminPageRenderer.esc(mediaPolicy.turnUsername()));
    }

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>(fs.status());
        s.put("invites", metrics.inviteCount());
        s.put("rejects", metrics.rejectCount());
        s.put("dialogs", metrics.activeDialogs());
        s.put("iceRequired", mediaPolicy.iceRequired() || mediaPolicy.rtpRedirect());
        s.put("requireTurn", mediaPolicy.requireTurn());
        s.put("rtpRedirect", mediaPolicy.rtpRedirect());
        return s;
    }

    @POST
    @Path("/calls/hangup")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response hangup(Map<String, String> body,
                           @HeaderParam("Cookie") String cookie,
                           @HeaderParam("X-SF-Admin-Key") String key,
                           @QueryParam("key") String keyQ) {
        if (!isAdmin(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "ADMIN only")).build();
        }
        String uuid = body == null ? null : body.get("uuid");
        if (uuid == null || uuid.isBlank() || !uuid.matches("[0-9a-fA-F\\-]{16,64}")) {
            return Response.status(400).entity(Map.of("ok", false, "message", "invalid uuid")).build();
        }
        String out = esl.api("uuid_kill " + uuid.trim());
        boolean ok = !out.startsWith("-ERR");
        return Response.ok(Map.of("ok", ok, "message", out)).build();
    }

    @POST
    @Path("/{action:apply|start|stop}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response action(@PathParam("action") String action,
                           @HeaderParam("Cookie") String cookie,
                           @HeaderParam("X-SF-Admin-Key") String key,
                           @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "operator access required")).build();
        }
        String result = switch (action) {
            case "apply" -> esl.api("reloadxml");
            case "start" -> esl.api("sofia profile external start");
            default -> esl.api("sofia profile external stop");
        };
        boolean ok = !result.startsWith("-ERR");
        return Response.ok(Map.of("ok", ok, "message", result, "apply", action)).build();
    }

    @POST
    @Path("/esl")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response eslConsole(@FormParam("command") String command,
                               @HeaderParam("Cookie") String cookie,
                               @HeaderParam("X-SF-Admin-Key") String key,
                               @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity("forbidden").build();
        }
        String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) {
            return Response.ok("<pre class=\"mono\">empty command</pre>").build();
        }
        String out = esl.api(cmd);
        return Response.ok("<pre class=\"mono\">" + AdminPageRenderer.esc(out) + "</pre>").build();
    }

    @POST
    @Path("/gateways")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createGateway(@FormParam("name") String name,
                                  @FormParam("profile") String profile,
                                  @HeaderParam("Cookie") String cookie,
                                  @HeaderParam("X-SF-Admin-Key") String key,
                                  @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).build();
        }
        SfGateway g = new SfGateway();
        g.name = name;
        g.profile = profile == null || profile.isBlank() ? "external" : profile;
        g.persist();
        return Response.seeOther(java.net.URI.create("/admin/gateways")).build();
    }

    @POST
    @Path("/directory")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createExtension(@FormParam("tenantId") String tenantId,
                                    @FormParam("extension") String extension,
                                    @FormParam("password") String password,
                                    @HeaderParam("Cookie") String cookie,
                                    @HeaderParam("X-SF-Admin-Key") String key,
                                    @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).build();
        }
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        if (session == null && keyOk) {
            session = new PortalAuthService.Session("", "api-key", PortalAuthService.ROLE_ADMIN, null, java.time.Instant.now());
        }
        if (tenantId == null || tenantId.isBlank() || extension == null || extension.isBlank()) {
            return Response.seeOther(java.net.URI.create("/admin/directory?error=fields")).build();
        }
        if (session != null && !tenantService.canAccess(session, tenantId)) {
            return Response.status(403).build();
        }
        SfExtension e = new SfExtension();
        e.tenantId = tenantId.trim();
        e.extension = extension.trim();
        String plain = password == null || password.isBlank() ? extension.trim() : password;
        e.passwordHash = et.elisa.security.SipDigestHasher.a1Hash(e.extension, sipRealm, plain);
        e.enabled = true;
        e.persist();
        return Response.seeOther(java.net.URI.create("/admin/directory")).build();
    }

    @POST
    @Path("/acl")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response createAcl(@FormParam("cidr") String cidr,
                              @FormParam("action") String action,
                              @HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).build();
        }
        String act = action == null ? "allow" : action.trim().toLowerCase();
        if (!"allow".equals(act) && !"deny".equals(act)) {
            return Response.seeOther(java.net.URI.create("/admin/acl?error=action")).build();
        }
        if (cidr == null || cidr.isBlank()) {
            return Response.seeOther(java.net.URI.create("/admin/acl?error=cidr")).build();
        }
        SfAclEntry row = new SfAclEntry();
        row.listName = SipIngressAclService.LIST_SIP_INGRESS;
        row.cidr = cidr.trim();
        row.action = act;
        row.enabled = true;
        row.persist();
        sipAcl.reload();
        return Response.seeOther(java.net.URI.create("/admin/acl")).build();
    }

    @POST
    @Path("/acl/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response deleteAcl(@FormParam("id") Long id,
                              @HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).build();
        }
        if (id != null) {
            SfAclEntry.deleteById(id);
            sipAcl.reload();
        }
        return Response.seeOther(java.net.URI.create("/admin/acl")).build();
    }

    @POST
    @Path("/acl/reload")
    @Produces(MediaType.APPLICATION_JSON)
    public Response reloadAcl(@HeaderParam("Cookie") String cookie,
                              @HeaderParam("X-SF-Admin-Key") String key,
                              @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false)).build();
        }
        sipAcl.reload();
        return Response.ok(Map.of("ok", true, "rules", sipAcl.ruleCount())).build();
    }

    private Response page(String template, String cookie, String key, String keyQ, boolean requireAuth) {
        return page(template, cookie, key, keyQ, requireAuth, "/admin");
    }

    private Response page(String template, String cookie, String key, String keyQ, boolean requireAuth, String activePath) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        if (requireAuth && session == null && !keyOk && !"login.html".equals(template)) {
            return Response.seeOther(java.net.URI.create("/admin/login")).build();
        }
        if (session != null && PortalAuthService.ROLE_TENANT.equals(session.role())
                && !"portal.html".equals(template) && !"login.html".equals(template)) {
            return Response.seeOther(java.net.URI.create("/admin/portal")).build();
        }
        Map<String, String> vars = nav.vars(session, activePath, keyOk);
        vars.put("{{SUCCESS_BANNER}}", "");
        vars.put("{{NOTICE}}", "");
        vars.put("{{HANGUP_UI}}", isAdmin(cookie, key, keyQ) ? "1" : "0");
        return Response.ok(pages.page(template, vars), MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .header("Vary", "HX-Request")
                .build();
    }

    private boolean isOperator(String cookie, String key, String keyQ) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        return PortalAuthService.mayOperate(session, keyOk);
    }

    private boolean isAdmin(String cookie, String key, String keyQ) {
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        if (keyOk) {
            return true;
        }
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        return session != null && session.isAdmin();
    }

    private static boolean isRestrictedAdminPage(String page) {
        return Set.of("esl", "acl", "tenants", "users", "sofia", "system", "codecs", "ice", "fs-control")
                .contains(page);
    }

    private static boolean isAdminOnlyPage(String page) {
        return "users".equals(page);
    }

    private String rows(String body, String headers) {
        if (body == null || body.isBlank()) {
            return pages.partial("gateway-rows.html", Map.of("{{EMPTY}}", "No records configured"));
        }
        String[] cols = headers.split("\\|");
        StringBuilder th = new StringBuilder("<table class=\"table-wrap\"><thead><tr>");
        for (String c : cols) {
            th.append("<th>").append(esc(c)).append("</th>");
        }
        th.append("</tr></thead><tbody>").append(body).append("</tbody></table>");
        return th.toString();
    }

    private static String esc(String s) {
        return AdminPageRenderer.esc(s == null ? "" : s);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
