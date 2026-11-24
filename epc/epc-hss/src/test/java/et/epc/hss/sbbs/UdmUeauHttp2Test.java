package et.epc.hss.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.SubscriberRecord;
import et.epc.hss.UeauService;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end Nudm-UEAU over the real {@code ra-http-server} in a {@link MicroSleeContainer}:
 * AUSF-side Vert.x client speaks HTTP/2 cleartext with <em>prior knowledge</em> (no HTTP/1.1
 * Upgrade, TS 29.500 §5.2.2) to {@link UdmUeauSbb}. An HTTP/1.1 client is also served.
 */
class UdmUeauHttp2Test {

    static final Imsi IMSI = new Imsi("001010000000001");
    static final String BODY = "{\"servingNetworkName\":\"5G:mnc001.mcc001.3gppnetwork.org\","
            + "\"ausfInstanceId\":\"8e1f2f1c-3a5d-4f4e-9d1a-0b2c3d4e5f60\"}";

    MicroSleeContainer container;
    HttpServerRaEndpoint http;
    Vertx clientVertx;
    int port;

    record Answer(HttpVersion version, int status, String contentType, String body) {}

    @BeforeEach
    void setUp() {
        HeapHssRepository repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        UeauService ueau = new UeauService(repo, secrets);

        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64).preferVirtualThreads(false)
                .sbbPoolMin(2).sbbPoolMax(16).sbbPerVirtualThread(false).build());
        container.registerSbbType(UdmUeauSbb.class, () -> new UdmUeauSbb(ueau));
        container.mapEventToSbb(HttpWebRequestEvent.class, UdmUeauSbb.SBB_NAME);

        HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
        ra.setHost("127.0.0.1");
        ra.setPort(0);
        ra.setResponseTimeoutMs(5_000);
        http = new HttpServerRaEndpoint(ra);
        container.registerRa(http, http);
        container.start();
        port = http.port();
        assertTrue(port > 0, "RA bound an ephemeral port");

        clientVertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clientVertx != null) {
            clientVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
        if (container != null) {
            container.stop();
        }
    }

    HttpClient client(HttpVersion version) {
        HttpClientOptions o = new HttpClientOptions().setProtocolVersion(version);
        if (version == HttpVersion.HTTP_2) {
            o.setHttp2ClearTextUpgrade(false); // prior knowledge: h2 preface on a fresh TCP connection
        }
        return clientVertx.createHttpClient(o);
    }

    Answer post(HttpClient c, String supiOrSuci) throws Exception {
        return c.request(HttpMethod.POST, port, "127.0.0.1",
                        "/nudm-ueau/v1/" + supiOrSuci + "/security-information/generate-auth-data")
                .compose(req -> req.putHeader("Content-Type", "application/json").send(BODY))
                .compose(resp -> resp.body().map(b -> new Answer(resp.version(), resp.statusCode(),
                        resp.getHeader("Content-Type"), b.toString())))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void ausfGetsA5gHeAkaVectorOverH2cPriorKnowledge() throws Exception {
        HttpClient c = client(HttpVersion.HTTP_2);
        Answer ok = post(c, "imsi-" + IMSI.digits());
        assertEquals(HttpVersion.HTTP_2, ok.version(), "h2c prior knowledge, no downgrade");
        assertEquals(200, ok.status());
        assertEquals("application/json", ok.contentType());
        assertTrue(ok.body().contains("5G_HE_AKA"));
        JsonObject av = new JsonObject(ok.body()).getJsonObject("authenticationVector");
        assertEquals(64, av.getString("kausf").length());

        Answer missing = post(c, "imsi-001019999999999");
        assertEquals(HttpVersion.HTTP_2, missing.version());
        assertEquals(404, missing.status());
        assertEquals("application/problem+json", missing.contentType());
        assertEquals(UeauService.USER_NOT_FOUND, new JsonObject(missing.body()).getString("cause"));
        c.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @Test
    void http11ClientIsServedToo() throws Exception {
        HttpClient c = client(HttpVersion.HTTP_1_1);
        Answer ok = post(c, "suci-0-001-01-0000-0-0-0000000001");
        assertEquals(HttpVersion.HTTP_1_1, ok.version());
        assertEquals(200, ok.status());
        assertTrue(ok.body().contains("5G_HE_AKA"));
        c.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
