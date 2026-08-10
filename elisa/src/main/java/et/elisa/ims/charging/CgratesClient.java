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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Quarkus CDI HTTP JSON-RPC client for CGRates CDRsV1. Soft-fail when disabled/down.
 */
@ApplicationScoped
public class CgratesClient {

    private static final Logger LOG = LogManager.getLogger(CgratesClient.class);

    @Inject CgratesConfig config;

    private HttpClient http;
    private ObjectMapper mapper;

    public CgratesClient() {}

    /** Test / non-CDI construction. */
    public CgratesClient(CgratesConfig config, HttpClient http, ObjectMapper mapper) {
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

    public record Result(boolean attempted, boolean ok, String detail) {
        public static Result skipped(String reason) {
            return new Result(false, false, reason);
        }

        public static Result softFail(String reason) {
            return new Result(true, false, reason);
        }

        public static Result success(String detail) {
            return new Result(true, true, detail);
        }
    }

    public boolean isCdrsPathActive() {
        return config != null && config.cdrsDualWriteActive();
    }

    /**
     * POST {@code CDRsV1.ProcessCDR}. Soft-fail: never throws to caller for transport errors.
     */
    public Result processCdr(CgratesCdrEvent event) {
        if (config == null || !config.cdrsDualWriteActive()) {
            return Result.skipped("cdrs-dual-write-inactive");
        }
        if (event == null || event.account() == null || event.account().isBlank()) {
            return Result.skipped("no-account");
        }
        try {
            ensureInit();
            ObjectNode body = CgratesJsonRpc.processCdrRequest(mapper, event);
            String json = mapper.writeValueAsString(body);
            String endpoint = jsonRpcUrl(config.url());
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofMillis(config.timeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                LOG.warn("CGRates CDRsV1 HTTP {} url={} icid={}", resp.statusCode(), endpoint, event.icid());
                return Result.softFail("http-" + resp.statusCode());
            }
            JsonNode parsed = mapper.readTree(resp.body() == null ? "{}" : resp.body());
            if (parsed.has("error") && !parsed.get("error").isNull()) {
                LOG.warn("CGRates CDRsV1 RPC error icid={}: {}", event.icid(), parsed.get("error"));
                return Result.softFail("rpc-error");
            }
            LOG.debug("CGRates CDRsV1 ok account={} icid={} sipCallId={}",
                    event.account(), event.icid(), event.sipCallId());
            return Result.success("ok");
        } catch (Throwable t) {
            LOG.warn("CGRates CDRsV1 soft-fail icid={}: {}", event.icid(), t.toString());
            return Result.softFail(t.getClass().getSimpleName());
        }
    }

    /** Expose built JSON for unit tests (no HTTP). */
    public String buildProcessCdrJson(CgratesCdrEvent event) throws Exception {
        ensureInit();
        return mapper.writeValueAsString(CgratesJsonRpc.processCdrRequest(mapper, event));
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

    static String jsonRpcUrl(String base) {
        String u = base == null ? "http://127.0.0.1:2080" : base.trim();
        if (u.endsWith("/jsonrpc") || u.endsWith("/")) {
            return u.endsWith("/") ? u.substring(0, u.length() - 1) + "/jsonrpc" : u;
        }
        return u + "/jsonrpc";
    }
}
