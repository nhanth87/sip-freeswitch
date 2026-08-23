package et.epc.host;

import et.epc.api.link.PeerLink;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/epc")
public class EpcStatusResource {

    @Inject
    EpcRuntime runtime;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodeId", runtime.bootstrap().cluster().nodeId());
        out.put("cluster", runtime.bootstrap().cluster().clusterName());
        out.put("mode", runtime.bootstrap().cluster().mode().name());
        out.put("wired", runtime.bootstrap().wired());
        Map<String, Object> s5c = new LinkedHashMap<>();
        s5c.put("wire", runtime.s5cWire);
        s5c.put("pgwcPeerHost", runtime.s5cPgwcPeerHost == null ? "" : runtime.s5cPgwcPeerHost);
        s5c.put("pgwcPeerPort", runtime.s5cPgwcPeerPort);
        out.put("s5c", s5c);
        out.put("roles", Map.of(
                "hss", runtime.bootstrap().roles().hss(),
                "sgwc", runtime.bootstrap().roles().sgwc(),
                "pgwc", runtime.bootstrap().roles().pgwc(),
                "pcrf", runtime.bootstrap().roles().pcrf(),
                "pcef", runtime.bootstrap().roles().pcef(),
                "up", runtime.bootstrap().roles().up()));
        Map<String, Object> links = new LinkedHashMap<>();
        for (Map.Entry<String, PeerLink> e : runtime.board().snapshot().entrySet()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("localListen", e.getValue().localListen());
            link.put("live", e.getValue().live());
            link.put("evidence", e.getValue().evidence() == null ? "" : e.getValue().evidence());
            links.put(e.getKey(), link);
        }
        out.put("links", links);
        out.put("note", "live is peer evidence only; health/LISTEN is not UP");
        // Additive observability — Docker HEALTHCHECK + existing keys untouched.
        out.put("config", "/epc/config");
        out.put("dashboard", "/epc/");
        out.put("datastore", "epc-datastore"); // Postgres HSS datastore (DATASTORE wiring at boot)
        return out;
    }
}
