package et.epc.hss.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.httpserver.command.HttpServerCommand.HttpResponseExCommand;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HssRepository;
import et.epc.hss.SubscriberRecord;
import et.epc.hss.UeauService;
import io.vertx.core.json.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Nudm-UEAU HTTP mapping on ra-http-server commands (TS 29.503 §6.3.3.2.4, TS 29.500 §5.2.7). */
class UdmUeauSbbTest {

    static final Imsi IMSI = new Imsi("001010000000001");
    static final String SUPI = "imsi-001010000000001";
    static final String PATH = "/nudm-ueau/v1/" + SUPI + "/security-information/generate-auth-data";
    static final String BODY = "{\"servingNetworkName\":\"5G:mnc001.mcc001.3gppnetwork.org\","
            + "\"ausfInstanceId\":\"8e1f2f1c-3a5d-4f4e-9d1a-0b2c3d4e5f60\"}";

    final List<OutboundCommand> sent = new ArrayList<>();
    HeapHssRepository repo;
    UdmUeauSbb sbb;

    @BeforeEach
    void setUp() throws Exception {
        repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        sbb = withPort(new UdmUeauSbb(new UeauService(repo, secrets)));
    }

    UdmUeauSbb withPort(UdmUeauSbb s) throws Exception {
        Field f = UdmUeauSbb.class.getDeclaredField("httpRa");
        f.setAccessible(true);
        f.set(s, (RaCommandPort) sent::add);
        return s;
    }

    HttpResponseExCommand call(String method, String path, String body) {
        sbb.onEvent(new HttpWebRequestEvent("s-" + sent.size(), method, path,
                Map.of("Content-Type", "application/json"), body), null);
        assertEquals(1, sent.size(), "exactly one response command per request");
        HttpResponseExCommand c = assertInstanceOf(HttpResponseExCommand.class, sent.remove(0));
        assertNull(c.binaryBody());
        return c;
    }

    static JsonObject assertProblem(HttpResponseExCommand c, int status, String cause) {
        assertEquals(status, c.statusCode());
        assertEquals("application/problem+json", c.contentType());
        JsonObject p = new JsonObject(c.textBody());
        assertEquals(status, p.getInteger("status"));
        assertEquals(cause, p.getString("cause"));
        assertTrue(p.containsKey("title"));
        return p;
    }

    @Test
    void generateAuthDataAnswers200WithTs29503Fields() {
        HttpResponseExCommand c = call("POST", PATH, BODY);
        assertEquals("s-0", c.sessionId());
        assertEquals(200, c.statusCode());
        assertEquals("application/json", c.contentType());
        JsonObject j = new JsonObject(c.textBody());
        assertEquals("5G_AKA", j.getString("authType"));
        assertEquals(SUPI, j.getString("supi"));
        JsonObject av = j.getJsonObject("authenticationVector");
        assertEquals("5G_HE_AKA", av.getString("avType"));
        assertEquals(32, av.getString("rand").length());
        assertEquals(32, av.getString("xresStar").length());
        assertEquals(32, av.getString("autn").length());
        assertEquals(64, av.getString("kausf").length());
        assertTrue(repo.find(IMSI).orElseThrow().sqn() > 0, "one SQN reserved");
    }

    @Test
    void nullSchemeSuciPathResolvesToSupi() {
        String suci = "suci-0-001-01-0000-0-0-0000000001";
        HttpResponseExCommand c = call("POST",
                "/nudm-ueau/v1/" + suci + "/security-information/generate-auth-data", BODY);
        assertEquals(200, c.statusCode());
        assertEquals(SUPI, new JsonObject(c.textBody()).getString("supi"));
    }

    @Test
    void protectedSuciIsUnsupportedScheme() {
        HttpResponseExCommand c = call("POST",
                "/nudm-ueau/v1/suci-0-001-01-0000-1-1-abcdef/security-information/generate-auth-data", BODY);
        assertProblem(c, 501, UeauService.UNSUPPORTED_PROTECTION_SCHEME);
    }

    @Test
    void unknownSupiIsUserNotFoundProblem() {
        HttpResponseExCommand c = call("POST",
                "/nudm-ueau/v1/imsi-001019999999999/security-information/generate-auth-data", BODY);
        assertProblem(c, 404, UeauService.USER_NOT_FOUND);
    }

    @Test
    void otherPathsAreResourceUriStructureNotFound() {
        assertProblem(call("POST", "/nudm-ueau/v1/" + SUPI + "/auth-events", BODY),
                404, UdmUeauSbb.RESOURCE_URI_STRUCTURE_NOT_FOUND);
        assertProblem(call("GET", "/nudm-sdm/v2/" + SUPI, null), 404, UdmUeauSbb.RESOURCE_URI_STRUCTURE_NOT_FOUND);
        assertProblem(call("POST", "/nudm-ueau/v1//security-information/generate-auth-data", BODY),
                404, UdmUeauSbb.RESOURCE_URI_STRUCTURE_NOT_FOUND);
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn(), "no SQN spent on a bad URI");
    }

    @Test
    void wrongMethodIs405WithAllowHeader() {
        HttpResponseExCommand c = call("GET", PATH, null);
        assertEquals(405, c.statusCode());
        assertEquals("application/problem+json", c.contentType());
        assertEquals("POST", c.headers().get("Allow"));
        JsonObject p = new JsonObject(c.textBody());
        assertEquals(405, p.getInteger("status"));
        assertFalse(p.containsKey("cause"), "TS 29.500 has no application cause for 405");
    }

    @Test
    void malformedJsonIsInvalidMsgFormat() {
        assertProblem(call("POST", PATH, "{\"servingNetworkName\":"), 400, UdmUeauSbb.INVALID_MSG_FORMAT);
        assertProblem(call("POST", PATH, "[1,2]"), 400, UdmUeauSbb.INVALID_MSG_FORMAT);
        assertProblem(call("POST", PATH, "null"), 400, UdmUeauSbb.INVALID_MSG_FORMAT);
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void emptyBodyIsMandatoryIeMissing() {
        assertProblem(call("POST", PATH, null), 400, UeauService.MANDATORY_IE_MISSING);
    }

    @Test
    void wrongJsonTypesAreIeIncorrect() {
        assertProblem(call("POST", PATH, "{\"servingNetworkName\":5,\"ausfInstanceId\":\"x\"}"),
                400, UeauService.MANDATORY_IE_INCORRECT);
        assertProblem(call("POST", PATH, BODY.replace("}", ",\"resynchronizationInfo\":\"abc\"}")),
                400, UeauService.OPTIONAL_IE_INCORRECT);
        assertProblem(call("POST", PATH, BODY.replace("}", ",\"resynchronizationInfo\":{\"rand\":1}}")),
                400, UeauService.OPTIONAL_IE_INCORRECT);
    }

    @Test
    void serviceExceptionIsSystemFailure500() throws Exception {
        sbb = withPort(new UdmUeauSbb(brokenService()));
        HttpResponseExCommand c = call("POST", PATH, BODY);
        JsonObject p = assertProblem(c, 500, UeauService.SYSTEM_FAILURE);
        assertFalse(p.encode().contains("boom"), "exception text never reaches the wire");
    }

    @Test
    void responseToStringNeverPrintsTheBody() {
        UdmUeauSbb.Response r = sbb.handle("POST", PATH, BODY);
        assertEquals(200, r.status());
        assertFalse(r.toString().contains(new JsonObject(r.body())
                .getJsonObject("authenticationVector").getString("kausf")));
    }

    @Test
    void missingCommandPortDoesNotThrow() {
        new UdmUeauSbb(new UeauService(repo, new HeapSecretStore()))
                .onEvent(new HttpWebRequestEvent("x", "POST", PATH, Map.of(), BODY), null);
        assertTrue(sent.isEmpty());
    }

    /** A UeauService whose repository throws on every call. */
    static UeauService brokenService() {
        HssRepository broken = (HssRepository) Proxy.newProxyInstance(UdmUeauSbbTest.class.getClassLoader(),
                new Class<?>[] {HssRepository.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("boom");
                });
        return new UeauService(broken, new HeapSecretStore());
    }
}
