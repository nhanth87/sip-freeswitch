package et.elisa.cdr;

import com.fasterxml.jackson.databind.JsonNode;
import et.elisa.persist.SfCdr;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.Map;

@Path("/cdr/json")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CdrIngestResource {

    @POST
    @Transactional
    public Map<String, Object> ingest(JsonNode cdr) {
        String uuid = cdr.path("uuid").asText(cdr.path("variable_uuid").asText(""));
        SfCdr row = new SfCdr();
        row.callUuid = uuid;
        row.payload = cdr.toString();
        row.createdAt = Instant.now();
        row.persist();
        return Map.of("ok", true, "callUuid", uuid);
    }
}
