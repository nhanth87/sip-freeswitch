package et.elisa.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import et.elisa.persist.SfCdr;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/cdr/json")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CdrIngestResource {

    @Inject CdrIngestService ingest;

    @POST
    public Map<String, Object> ingest(JsonNode cdr) {
        SfCdr row = ingest.ingest(cdr);
        return Map.of(
                "ok", true,
                "callUuid", row.callUuid == null ? "" : row.callUuid,
                "tenantId", row.tenantId == null ? "" : row.tenantId);
    }
}
