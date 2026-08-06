package et.elisa.media;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-facing ICE/TURN config (UA must use these for media). */
@Path("/media")
public class MediaIceConfigResource {

    @Inject
    MediaIceTurnPolicy policy;

    @GET
    @Path("/ice-config")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> iceConfig() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("iceRequired", policy.iceRequired() || policy.rtpRedirect());
        m.put("requireTurn", policy.requireTurn());
        m.put("rtpRedirect", policy.rtpRedirect());
        m.put("iceServers", List.of(
                Map.of("urls", policy.stunUri()),
                Map.of(
                        "urls", policy.turnUri(),
                        "username", policy.turnUsername(),
                        "credential", policy.turnCredential()
                )
        ));
        m.put("policy", policy.rtpRedirect()
                ? "rtp_redirect: media must use TURN relay so RTP can cross the firewall (Elisa does not relay RTP)"
                : "Media must traverse ICE; TURN relay required in SDP when requireTurn=true");
        return m;
    }
}
