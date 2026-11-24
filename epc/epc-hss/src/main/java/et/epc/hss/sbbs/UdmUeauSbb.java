package et.epc.hss.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.ra.httpserver.command.HttpServerCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import et.epc.hss.UeauService;
import et.epc.hss.UeauService.AuthenticationInfoRequest;
import et.epc.hss.UeauService.AuthenticationInfoResult;
import et.epc.hss.UeauService.Av5GHeAka;
import et.epc.hss.UeauService.Outcome;
import et.epc.hss.UeauService.ProblemDetails;
import et.epc.hss.UeauService.ResynchronizationInfo;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * UDM SBI face of the HSS: Nudm_UEAuthentication <em>Get</em> (TS 29.503 §5.4.2.2) on
 * {@code ra-http-server} (Vert.x, HTTP/2 cleartext prior knowledge per TS 29.500 §5.2.2, HTTP/1.1
 * also accepted). The RA owns the socket and HTTP framing; this SBB only routes, maps JSON ⇄ the
 * {@link UeauService} records and answers with one {@link HttpServerCommand.HttpResponseExCommand}
 * per request. All procedure logic (identity, SN authorisation, SQN, 5G HE AV) lives in
 * {@link UeauService}.
 *
 * <h2>Resource (TS 29.503 §6.3.3.2.4)</h2>
 * <pre>
 *   POST {apiRoot}/nudm-ueau/v1/{supiOrSuci}/security-information/generate-auth-data
 *   body AuthenticationInfoRequest → 200 AuthenticationInfoResult | ProblemDetails
 * </pre>
 *
 * <h2>Errors outside {@link UeauService} (TS 29.500 §5.2.7.2, Table 5.2.7.2-1)</h2>
 * <ul>
 *   <li>any other URI → 404 {@code RESOURCE_URI_STRUCTURE_NOT_FOUND};</li>
 *   <li>the resource with a method other than POST → 405 with {@code Allow: POST} (RFC 9110 §15.5.6);
 *       TS 29.500 defines no application cause for 405, so the ProblemDetails carries none;</li>
 *   <li>a body that is not a JSON object → 400 {@code INVALID_MSG_FORMAT};</li>
 *   <li>an IE of the wrong JSON type → 400 {@code MANDATORY_IE_INCORRECT} / {@code OPTIONAL_IE_INCORRECT};</li>
 *   <li>anything unexpected → 500 {@code SYSTEM_FAILURE}; {@link #onEvent} never throws, so the RA's
 *       504 deadline is only the last resort for a missing command port.</li>
 * </ul>
 * Errors use {@code application/problem+json} (TS 29.500 §5.2.7.1).
 *
 * <h2>State and timers</h2>
 * Stateless per request: no CMP, no timers. The only per-request state is the RA {@code sessionId}
 * carried back in the response command. SQN state lives in the {@code HssRepository}.
 *
 * <h2>Secrets</h2>
 * Never logs a request or response body: the 200 body carries XRES* and K<sub>AUSF</sub>, and
 * {@link HttpWebRequestEvent#toString()} prints the body, so the event is never logged either.
 *
 * <p>No no-arg constructor on purpose: the container auto-deploys every APT-indexed SBB it can
 * instantiate under its annotation name, and an auto-deployed instance (with no {@link UeauService})
 * would take precedence over the {@code registerSbbType} factory for {@code mapEventToSbb}.
 */
@SbbAnnotation(name = UdmUeauSbb.SBB_NAME, vendor = "et.epc", version = "1.0")
public class UdmUeauSbb implements Sbb, SleeEventHandler {

    public static final String SBB_NAME = "UdmUeauSbb";
    /** {@code HttpServerRaEndpoint.getRaName()}. */
    public static final String HTTP_SERVER_RA = "http-server-ra";

    public static final String CONTENT_TYPE_JSON = "application/json";
    public static final String CONTENT_TYPE_PROBLEM = "application/problem+json";

    /** TS 29.500 Table 5.2.7.2-1 common causes used by this SBB. */
    public static final String RESOURCE_URI_STRUCTURE_NOT_FOUND = "RESOURCE_URI_STRUCTURE_NOT_FOUND";
    public static final String INVALID_MSG_FORMAT = "INVALID_MSG_FORMAT";

    private static final String API_PREFIX = "/nudm-ueau/v1/";
    /** {supiOrSuci} is one non-empty path segment. */
    private static final Pattern GENERATE_AUTH_DATA =
            Pattern.compile("/nudm-ueau/v1/([^/]+)/security-information/generate-auth-data/?");

    private static final Logger LOG = LogManager.getLogger(UdmUeauSbb.class);

    private final UeauService ueau;

    @InjectRa(name = HTTP_SERVER_RA)
    private volatile RaCommandPort httpRa;

    public UdmUeauSbb(UeauService ueau) {
        this.ueau = Objects.requireNonNull(ueau, "ueau");
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof HttpWebRequestEvent req)) {
            return;
        }
        Response response;
        try {
            response = handle(req.getMethod(), req.getPath(), req.getBody());
        } catch (RuntimeException | LinkageError unexpected) {
            // LinkageError too: a classpath clash (e.g. a stale Jackson under Vert.x JSON) must still
            // give the AUSF a 500, not leave it waiting for the RA's 504 deadline.
            // Class name only: an exception message could echo request content.
            LOG.error("[UDM] Nudm-UEAU session={} failed: {}", req.getSessionId(),
                    unexpected.getClass().getName());
            response = problem(500, UeauService.SYSTEM_FAILURE, "unexpected error");
        }
        send(req.getSessionId(), response);
    }

    /** Route + map; package-private for tests. Never returns null. */
    Response handle(String method, String path, String body) {
        Matcher m = path == null ? null : GENERATE_AUTH_DATA.matcher(path);
        if (m == null || !m.matches()) {
            return problem(404, RESOURCE_URI_STRUCTURE_NOT_FOUND,
                    path != null && path.startsWith(API_PREFIX)
                            ? "unknown Nudm-UEAU resource" : "unknown API");
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return new Response(405, CONTENT_TYPE_PROBLEM,
                    problemJson(405, null, "only POST is allowed on generate-auth-data"),
                    Map.of("Allow", "POST"));
        }
        String supiOrSuci;
        try {
            supiOrSuci = URLDecoder.decode(m.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException badEscape) {
            return problem(404, RESOURCE_URI_STRUCTURE_NOT_FOUND, "supiOrSuci is not a valid URI segment");
        }

        JsonObject json;
        try {
            json = body == null || body.isBlank() ? null : new JsonObject(body);
        } catch (DecodeException | ClassCastException notAnObject) {
            return problem(400, INVALID_MSG_FORMAT, "body is not a JSON AuthenticationInfoRequest object");
        }
        AuthenticationInfoRequest request = null;
        if (json != null) {
            Object sn = json.getValue("servingNetworkName");
            Object ausf = json.getValue("ausfInstanceId");
            if (!(sn == null || sn instanceof String) || !(ausf == null || ausf instanceof String)) {
                return problem(400, UeauService.MANDATORY_IE_INCORRECT,
                        "servingNetworkName and ausfInstanceId must be strings");
            }
            ResynchronizationInfo resync = null;
            Object r = json.getValue("resynchronizationInfo");
            if (r != null) {
                if (!(r instanceof JsonObject ro)
                        || !(ro.getValue("rand") == null || ro.getValue("rand") instanceof String)
                        || !(ro.getValue("auts") == null || ro.getValue("auts") instanceof String)) {
                    return problem(400, UeauService.OPTIONAL_IE_INCORRECT,
                            "resynchronizationInfo must be an object with string rand and auts");
                }
                resync = new ResynchronizationInfo(ro.getString("rand"), ro.getString("auts"));
            }
            request = new AuthenticationInfoRequest((String) sn, resync, (String) ausf);
        }

        Outcome outcome = ueau.generateAuthData(supiOrSuci, request);
        return switch (outcome) {
            case AuthenticationInfoResult ok -> new Response(200, CONTENT_TYPE_JSON, resultJson(ok), Map.of());
            case ProblemDetails p -> problem(p.status(), p.cause(), p.detail());
        };
    }

    // ---------------------------------------------------------------- JSON (TS 29.503 §6.3.6)

    static String resultJson(AuthenticationInfoResult r) {
        Av5GHeAka av = r.authenticationVector();
        JsonObject vector = new JsonObject()
                .put("avType", av.avType())
                .put("rand", av.rand())
                .put("xresStar", av.xresStar())
                .put("autn", av.autn())
                .put("kausf", av.kausf());
        JsonObject body = new JsonObject()
                .put("authType", r.authType())
                .put("authenticationVector", vector);
        if (r.supi() != null) {
            body.put("supi", r.supi());
        }
        return body.encode();
    }

    /** TS 29.571 ProblemDetails: {@code title}, {@code status}, {@code detail}, {@code cause}. */
    static String problemJson(int status, String cause, String detail) {
        JsonObject p = new JsonObject().put("title", reason(status)).put("status", status);
        if (detail != null) {
            p.put("detail", detail);
        }
        if (cause != null) {
            p.put("cause", cause);
        }
        return p.encode();
    }

    private static Response problem(int status, String cause, String detail) {
        return new Response(status, CONTENT_TYPE_PROBLEM, problemJson(status, cause, detail), Map.of());
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            default -> "HTTP " + status;
        };
    }

    // ---------------------------------------------------------------- RA

    private void send(String sessionId, Response r) {
        RaCommandPort port = httpRa;
        if (port == null) {
            LOG.warn("[UDM] {} not injected — session={} left to the RA response deadline",
                    HTTP_SERVER_RA, sessionId);
            return;
        }
        try {
            port.sendCommand(new HttpServerCommand.HttpResponseExCommand(
                    sessionId, r.status(), r.contentType(), r.body(), null, r.headers()));
        } catch (RuntimeException e) {
            LOG.warn("[UDM] response session={} status={} not sent: {}", sessionId, r.status(),
                    e.getClass().getName());
            return;
        }
        LOG.debug("[UDM] Nudm-UEAU session={} status={}", sessionId, r.status());
    }

    /** One HTTP answer. {@code toString()} omits the body (a 200 body carries XRES* and KAUSF). */
    record Response(int status, String contentType, String body, Map<String, String> headers) {
        @Override
        public String toString() {
            return "Response[status=" + status + ", contentType=" + contentType + ", body=<omitted>]";
        }
    }
}
