package et.elisa.ims.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import et.elisa.persist.SfCdr;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CgratesClientTest {

    @Test
    void processCdr_payloadContainsAccountIcidSipCallId() throws Exception {
        CgratesConfig cfg = CgratesConfig.forTest(true, true, true, false, "http://127.0.0.1:9");
        CgratesClient client = new CgratesClient(cfg, HttpClient.newHttpClient(), new ObjectMapper());
        String json = client.buildProcessCdrJson(sampleEvent());
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals("CDRsV1.ProcessCDR", root.get("method").asText());
        JsonNode cdr = root.get("params").get(0);
        assertEquals("251911000111", cdr.get("Account").asText());
        assertEquals("1000", cdr.get("Destination").asText());
        assertEquals("60s", cdr.get("Usage").asText());
        assertEquals("icid-abc", cdr.get("ExtraFields").get("icid").asText());
        assertEquals("sip-call-1", cdr.get("ExtraFields").get("sipCallId").asText());
    }

    @Test
    void processCdr_disabled_skipsWithoutHttp() {
        CgratesConfig cfg = CgratesConfig.forTest(false, true, true, false, "http://127.0.0.1:9");
        CgratesClient client = new CgratesClient(cfg, HttpClient.newHttpClient(), new ObjectMapper());
        CgratesClient.Result r = client.processCdr(sampleEvent());
        assertFalse(r.attempted());
        assertEquals("cdrs-dual-write-inactive", r.detail());
    }

    @Test
    void processCdr_softFailsWhenPeerDown() {
        CgratesConfig cfg = CgratesConfig.forTest(true, true, true, false, "http://127.0.0.1:1");
        CgratesClient client = new CgratesClient(cfg, HttpClient.newHttpClient(), new ObjectMapper());
        CgratesClient.Result r = client.processCdr(sampleEvent());
        assertTrue(r.attempted());
        assertFalse(r.ok());
    }

    @Test
    void processCdr_postsJsonRpcWhenUp() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jsonrpc", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] ok = "{\"jsonrpc\":\"2.0\",\"result\":\"OK\",\"id\":1}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            CgratesConfig cfg = CgratesConfig.forTest(true, true, true, false, "http://127.0.0.1:" + port);
            CgratesClient client = new CgratesClient(cfg, HttpClient.newHttpClient(), new ObjectMapper());
            CgratesClient.Result r = client.processCdr(sampleEvent());
            assertTrue(r.ok(), r.detail());
            assertTrue(body.get().contains("\"Account\":\"251911000111\""));
            assertTrue(body.get().contains("\"icid\":\"icid-abc\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void accountResolve_msisdnPreferredSoftImpi() {
        assertEquals("251911", ChargingAccountResolver.resolve("251911", "user@ims.lab"));
        assertEquals("user@ims.lab", ChargingAccountResolver.resolve(null, "user@ims.lab"));
        assertEquals(null, ChargingAccountResolver.resolve(null, null));
    }

    @Test
    void dualWrite_rfStubWhenFlagsOn() {
        CgratesConfig cfg = CgratesConfig.forTest(true, false, false, true, "http://127.0.0.1:9");
        AtomicReference<RfAcrRequest> captured = new AtomicReference<>();
        ChargingDualWrite dual = new ChargingDualWrite(cfg, null,
                new ChargingAccountResolver(null, cfg), req -> {
                    if (cfg.rfDualWriteActive()) {
                        captured.set(req);
                    }
                });
        SfCdr row = new SfCdr();
        row.msisdn = "251911";
        row.icid = "icid-1";
        row.sipCallId = "call-1";
        row.duration = 12;
        dual.afterCdrPersist(row);
        assertEquals("icid-1", captured.get().icid());
        assertEquals("251911", captured.get().account());
    }

    @Test
    void pendingStore_idempotentKeyNoDoubleSettle() {
        InMemoryPendingChargeStore store = new InMemoryPendingChargeStore();
        PendingCharge a = PendingCharge.pending("icid-x", "sip-y", "251911");
        assertEquals("icid:icid-x", a.key());
        store.enqueue(a);
        store.enqueue(PendingCharge.pending("icid-x", "other", "251911"));
        assertTrue(store.markSettled(a.key()));
        assertFalse(store.markSettled(a.key()));
    }

    @Test
    void jsonRpcUrl_appendsPath() {
        assertEquals("http://ocs:2080/jsonrpc", CgratesClient.jsonRpcUrl("http://ocs:2080"));
        assertEquals("http://ocs:2080/jsonrpc", CgratesClient.jsonRpcUrl("http://ocs:2080/jsonrpc"));
    }

    private static CgratesCdrEvent sampleEvent() {
        return new CgratesCdrEvent(
                "cgrates.org",
                "251911000111",
                "1000",
                "call",
                "elisa",
                "*pseudoprepaid",
                "60s",
                Instant.parse("2026-08-10T12:00:00Z"),
                Instant.parse("2026-08-10T12:00:00Z"),
                "icid-abc",
                "sip-call-1",
                "fs-uuid-1",
                "NORMAL_CLEARING");
    }
}
