package et.elisa.admin;

import et.elisa.esl.EslClient;
import et.elisa.esl.FsLinkStatusService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Path("/admin")
public class FsControlRoutes {

    @Inject AdminPageRenderer pages;
    @Inject AdminNavRenderer nav;
    @Inject PortalAuthService auth;
    @Inject FsConfService conf;
    @Inject EslClient esl;
    @Inject FsLinkStatusService fs;

    @ConfigProperty(name = "sf.admin.api-key")
    String apiKey;

    @GET
    @Path("/fs-control")
    @Produces(MediaType.TEXT_HTML)
    public Response page(@HeaderParam("Cookie") String cookie,
                         @HeaderParam("X-SF-Admin-Key") String key,
                         @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.seeOther(java.net.URI.create("/admin/login")).build();
        }
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        Map<String, String> vars = nav.vars(session, "/admin/fs-control", keyOk);
        vars.put("{{SUCCESS_BANNER}}", "");
        vars.put("{{NOTICE}}", "");
        vars.put("{{CONF_ROOT}}", AdminPageRenderer.esc(conf.confRoot().toString()));
        vars.put("{{CONF_OK}}", conf.confRootExists() ? "yes" : "no");
        return Response.ok(pages.page("fs-control.html", vars), MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .header("Vary", "HX-Request")
                .build();
    }

    @GET
    @Path("/fs-control/detail")
    @Produces(MediaType.TEXT_HTML)
    public Response detail(@HeaderParam("Cookie") String cookie,
                           @HeaderParam("X-SF-Admin-Key") String key,
                           @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity("<p class=\"hint\">forbidden</p>").build();
        }
        Map<String, Object> st = fs.status();
        boolean live = Boolean.TRUE.equals(st.get("eslLive")) || "true".equalsIgnoreCase(String.valueOf(st.get("eslLive")));
        String status = esl.api("status");
        String version = esl.api("version");
        String modules = esl.api("show modules as delim |");
        if (modules.length() > 2500) {
            modules = modules.substring(0, 2500) + "…";
        }
        return Response.ok(pages.partial("fs-detail.html", Map.of(
                "{{ESL_BADGE_CLASS}}", live ? "badge-live" : "badge-down",
                "{{ESL_LABEL}}", live ? "LIVE" : "DOWN",
                "{{CHANNELS}}", String.valueOf(st.getOrDefault("channels", 0)),
                "{{VERSION}}", AdminPageRenderer.esc(version),
                "{{STATUS}}", AdminPageRenderer.esc(status),
                "{{MODULES}}", AdminPageRenderer.esc(modules),
                "{{CONF_ROOT}}", AdminPageRenderer.esc(conf.confRoot().toString())
        ))).build();
    }

    @GET
    @Path("/fs-control/tree")
    @Produces(MediaType.TEXT_HTML)
    public Response tree(@QueryParam("path") String path,
                         @HeaderParam("Cookie") String cookie,
                         @HeaderParam("X-SF-Admin-Key") String key,
                         @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity("<p class=\"hint\">forbidden</p>").build();
        }
        try {
            String rel = path == null || path.isBlank() ? "" : path;
            List<Map<String, String>> entries = conf.list(rel);
            StringBuilder body = new StringBuilder();
            conf.parentPath(rel).ifPresent(parent -> body.append("<button type=\"button\" class=\"linkish\" data-fs-dir=\"")
                    .append(AdminPageRenderer.esc(parent))
                    .append("\">../</button>"));
            body.append("<p class=\"hint mono\">")
                    .append(AdminPageRenderer.esc(rel.isBlank() ? "." : rel))
                    .append("</p>");
            for (Map<String, String> e : entries) {
                String p = e.get("path");
                String name = e.get("name");
                if ("dir".equals(e.get("type"))) {
                    body.append("<button type=\"button\" class=\"linkish dir\" data-fs-dir=\"")
                            .append(AdminPageRenderer.esc(p))
                            .append("\">📁 ")
                            .append(AdminPageRenderer.esc(name))
                            .append("/</button>");
                } else {
                    body.append("<button type=\"button\" class=\"linkish\" data-fs-file=\"")
                            .append(AdminPageRenderer.esc(p))
                            .append("\">📄 ")
                            .append(AdminPageRenderer.esc(name))
                            .append("</button>");
                }
            }
            if (entries.isEmpty()) {
                body.append("<p class=\"hint\">Empty directory</p>");
            }
            return Response.ok(body.toString()).build();
        } catch (Exception ex) {
            return Response.status(400).entity("<p class=\"hint\">" + AdminPageRenderer.esc(ex.getMessage()) + "</p>").build();
        }
    }

    @GET
    @Path("/fs-control/file")
    @Produces(MediaType.APPLICATION_JSON)
    public Response file(@QueryParam("path") String path,
                         @HeaderParam("Cookie") String cookie,
                         @HeaderParam("X-SF-Admin-Key") String key,
                         @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "forbidden")).build();
        }
        try {
            String content = conf.readFile(path);
            return Response.ok(Map.of("ok", true, "path", path == null ? "" : path, "content", content)).build();
        } catch (Exception ex) {
            return Response.status(400).entity(Map.of("ok", false, "message", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/fs-control/file")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response saveFile(Map<String, String> body,
                             @HeaderParam("Cookie") String cookie,
                             @HeaderParam("X-SF-Admin-Key") String key,
                             @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "forbidden")).build();
        }
        try {
            String path = body == null ? null : body.get("path");
            String content = body == null ? null : body.get("content");
            conf.writeFile(path, content);
            return Response.ok(Map.of("ok", true, "message", "Saved " + path)).build();
        } catch (Exception ex) {
            return Response.status(400).entity(Map.of("ok", false, "message", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/fs-control/mkdir")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response mkdir(Map<String, String> body,
                          @HeaderParam("Cookie") String cookie,
                          @HeaderParam("X-SF-Admin-Key") String key,
                          @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "forbidden")).build();
        }
        try {
            String path = body == null ? null : body.get("path");
            conf.mkdir(path);
            return Response.ok(Map.of("ok", true, "message", "Created " + path)).build();
        } catch (Exception ex) {
            return Response.status(400).entity(Map.of("ok", false, "message", ex.getMessage())).build();
        }
    }

    @POST
    @Path("/fs-control/soft-reload")
    @Produces(MediaType.APPLICATION_JSON)
    public Response softReload(@HeaderParam("Cookie") String cookie,
                               @HeaderParam("X-SF-Admin-Key") String key,
                               @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "forbidden")).build();
        }
        String reload = esl.api("reloadxml");
        String rescan = esl.api("sofia profile external rescan");
        boolean ok = !reload.startsWith("-ERR");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("message", ok ? "Soft reload done" : reload);
        out.put("reloadxml", reload);
        out.put("rescan", rescan);
        return Response.ok(out).build();
    }

    @POST
    @Path("/fs-control/hard-restart")
    @Produces(MediaType.APPLICATION_JSON)
    public Response hardRestart(@HeaderParam("Cookie") String cookie,
                                @HeaderParam("X-SF-Admin-Key") String key,
                                @QueryParam("key") String keyQ) {
        if (!isOperator(cookie, key, keyQ)) {
            return Response.status(403).entity(Map.of("ok", false, "message", "forbidden")).build();
        }
        try {
            return Response.ok(conf.hardRestart()).build();
        } catch (Exception ex) {
            return Response.status(500).entity(Map.of("ok", false, "message", ex.getMessage())).build();
        }
    }

    private boolean isOperator(String cookie, String key, String keyQ) {
        PortalAuthService.Session session = auth.resolve(cookie).orElse(null);
        boolean keyOk = apiKey != null && (apiKey.equals(key) || apiKey.equals(keyQ));
        return PortalAuthService.mayOperate(session, keyOk);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
