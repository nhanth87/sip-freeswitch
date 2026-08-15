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
        out.put("roles", Map.of(
                "hss", runtime.bootstrap().roles().hss(),
                "sgwc", runtime.bootstrap().roles().sgwc(),
                "pgwc", runtime.bootstrap().roles().pgwc()));
        Map<String, Object> links = new LinkedHashMap<>();
        for (Map.Entry<String, PeerLink> e : runtime.board().snapshot().entrySet()) {
            links.put(e.getKey(), Map.of(
                    "localListen", e.getValue().localListen(),
                    "live", e.getValue().live(),
                    "evidence", e.getValue().evidence()));
        }
        out.put("links", links);
        out.put("note", "live is peer evidence only; health/LISTEN is not UP");
        return out;
    }
}
