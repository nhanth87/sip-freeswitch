package et.elisa.ims.charging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.BiFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CGRates SessionS JSON-RPC client (Ro-like). Soft-fail when disabled/down — peer truth = HTTP evidence.
 */
@ApplicationScoped
public class SessionSClient {

    private static final Logger LOG = LogManager.getLogger(SessionSClient.class);

    @Inject CgratesConfig config;

    private HttpClient http;
    private ObjectMapper mapper;

    public SessionSClient() {}

    public SessionSClient(CgratesConfig config, HttpClient http, ObjectMapper mapper) {
        this.config = config;
        this.http = http;
        this.mapper = mapper == null ? new ObjectMapper() : mapper;
    }

    @PostConstruct
    void init() {
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                    .build();
        }
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
    }

    public record Result(boolean attempted, boolean ok, boolean peerDown, boolean creditDenied, String detail) {
        public static Result skipped(String reason) {
            return new Result(false, false, false, false, reason);
        }

        public static Result softFail(String reason, boolean peerDown) {
            return new Result(true, false, peerDown, false, reason);
        }

        public static Result denied(String reason) {
            return new Result(true, false, false, true, reason);
        }

        public static Result success(String detail) {
            return new Result(true, true, false, false, detail);
        }
    }

    public Result initiateSession(SessionSRequest request) {
        return invoke("InitiateSession", request, CgratesJsonRpc::initiateSessionRequest);
    }

    public Result updateSession(SessionSRequest request) {
        return invoke("UpdateSession", request, CgratesJsonRpc::updateSessionRequest);
    }

    public Result terminateSession(SessionSRequest request) {
        return invoke("TerminateSession", request, CgratesJsonRpc::terminateSessionRequest);
    }

    public String buildInitiateJson(SessionSRequest request) throws Exception {
        ensureInit();
        return mapper.writeValueAsString(CgratesJsonRpc.initiateSessionRequest(mapper, request));
    }

    public String buildTerminateJson(SessionSRequest request) throws Exception {
        ensureInit();
        return mapper.writeValueAsString(CgratesJsonRpc.terminateSessionRequest(mapper, request));
    }

    private Result invoke(String label, SessionSRequest request,
                          BiFunction<ObjectMapper, SessionSRequest, ObjectNode> builder) {
        if (config == null || !config.enabled() || !config.sessionSEnabled()) {
            return Result.skipped("sessions-inactive");
        }
        if (request == null || request.account() == null || request.account().isBlank()) {
            return Result.skipped("no-account");
        }
        try {
            ensureInit();
            ObjectNode body = builder.apply(mapper, request);
            String json = mapper.writeValueAsString(body);
            String endpoint = CgratesClient.jsonRpcUrl(config.url());
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.warn("CGRates SessionS {} HTTP {} icid={}", label, resp.statusCode(), request.icid());
                return Result.softFail("http-" + resp.statusCode(), true);
            }
            JsonNode parsed = mapper.readTree(resp.body() == null ? "{}" : resp.body());
            if (parsed.has("error") && !parsed.get("error").isNull()) {
                LOG.warn("CGRates SessionS {} RPC error icid={}: {}", label, request.icid(), parsed.get("error"));
                return Result.denied("rpc-error");
            }
            if (isZeroMaxUsage(parsed.get("result"))) {
                return Result.denied("max-usage-0");
            }
            LOG.debug("CGRates SessionS {} ok account={} icid={}", label, request.account(), request.icid());
            return Result.success("ok");
        } catch (Throwable t) {
            LOG.warn("CGRates SessionS {} soft-fail icid={}: {}", label, request.icid(), t.toString());
            return Result.softFail(t.getClass().getSimpleName(), true);
        }
    }

    static boolean isZeroMaxUsage(JsonNode result) {
        if (result == null || result.isNull()) {
            return false;
        }
        if (result.isNumber() && result.asLong() == 0L) {
            return true;
        }
        if (result.isObject()) {
            JsonNode max = result.get("MaxUsage");
            if (max != null && max.isNumber() && max.asLong() == 0L) {
                return true;
            }
            if (max != null && max.isTextual()) {
                String t = max.asText().trim();
                return "0".equals(t) || "0s".equalsIgnoreCase(t) || "0ns".equalsIgnoreCase(t);
            }
        }
        return false;
    }

    private void ensureInit() {
        if (mapper == null) {
            mapper = new ObjectMapper();
        }
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(config == null ? 2000 : config.timeoutMs()))
                    .build();
        }
    }
}
