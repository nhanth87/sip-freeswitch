package et.epc.host;

import et.epc.pcrf.PccRule;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * PCC rule provisioning on the EPC host (TS 29.212 §4.5.2 PCRF-initiated): the rule lands
 * in Postgres ({@code epc_pcc_rule}), the cached rule set is invalidated and a Gx RAR with
 * Charging-Rule-Install goes to every live IP-CAN session on the APN.
 *
 * <pre>
 * curl -X PUT localhost:8099/epc/pcrf/rules/internet/video-gbr \
 *   -d qci=2 -d arp=5 -d mbrUl=2000000 -d mbrDl=8000000 -d gbrUl=1000000 -d gbrDl=4000000
 * </pre>
 * Same posture as {@code /epc/config}: EPC host admin port, never the Elisa edge.
 */
@Path("/epc/pcrf/rules")
public class EpcPcrfRuleResource {

    @Inject
    EpcRuntime runtime;

    @PUT
    @Path("{apn}/{name}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public String put(@PathParam("apn") String apn,
                      @PathParam("name") String name,
                      @FormParam("qci") @DefaultValue("9") int qci,
                      @FormParam("arp") @DefaultValue("8") int arp,
                      @FormParam("preemptCap") @DefaultValue("false") boolean preemptCap,
                      @FormParam("preemptVuln") @DefaultValue("true") boolean preemptVuln,
                      @FormParam("mbrUl") @DefaultValue("0") long mbrUl,
                      @FormParam("mbrDl") @DefaultValue("0") long mbrDl,
                      @FormParam("gbrUl") @DefaultValue("0") long gbrUl,
                      @FormParam("gbrDl") @DefaultValue("0") long gbrDl,
                      @FormParam("ratingGroup") Integer ratingGroup,
                      @FormParam("serviceId") String serviceId,
                      @FormParam("flowDescription") @DefaultValue("[]") String flowDescription,
                      @FormParam("gatingOpen") @DefaultValue("true") boolean gatingOpen) {
        EpcBootstrap bootstrap = runtime == null ? null : runtime.bootstrap();
        if (bootstrap == null) {
            throw new WebApplicationException("EPC not started", Response.Status.SERVICE_UNAVAILABLE);
        }
        PccRule rule;
        try {
            rule = new PccRule(name, qci, arp, preemptCap, preemptVuln, mbrUl, mbrDl, gbrUl, gbrDl,
                    ratingGroup, serviceId, flowDescription, gatingOpen);
        } catch (IllegalArgumentException ex) {
            throw new WebApplicationException(ex.getMessage(), Response.Status.BAD_REQUEST);
        }
        int pushed;
        try {
            pushed = bootstrap.updatePccRule(apn, rule);
        } catch (IllegalStateException ex) {
            throw new WebApplicationException(ex.getMessage(), Response.Status.SERVICE_UNAVAILABLE);
        }
        return "{\"apn\":\"" + json(apn) + "\",\"rule\":\"" + json(name) + "\",\"rarSessions\":" + pushed + "}";
    }

    private static String json(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
