package et.elisa.ims.spec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.aka.AkaMd5;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.ims.scscf.IscThirdPartyRegister;
import et.elisa.ims.scscf.ScscfRegisterSbb;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.ims.support.UeAka;
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
import et.elisa.persist.ImsSubscriber;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TS 24.229 / TS 33.203 REGISTER + IMS AKA coverage — designed to fail on protocol bugs.
 */
class Ts24229RegisterFlowTest {

    private static final String IMPU = "sip:alice@ims.lab";
    private static final String IMPI = "alice@ims.lab";
    private static final String CONTACT = "sip:alice@10.0.0.8:5060";
    private static final String CALL = "reg-call-1";
    private static final byte[] XRES = AkaMd5.parseHex("a1b2c3d4e5f60718");
    private static final String RAND = "11".repeat(16);
    private static final String AUTN = "22".repeat(16);
    private static final String RAND_AUTN = RAND + AUTN;
    /** RFC 3310 §3.2 nonce = base64(RAND ‖ AUTN). */
    private static final String NONCE = AkaMd5.nonce(AkaMd5.parseHex(RAND), AkaMd5.parseHex(AUTN));

    /** RFC 3310 answer of a UE whose RES equals {@code res}. */
    private static String akaAuth(String nonce, byte[] res) {
        return UeAka.authorization(IMPI, "ims.lab", "sip:ims.lab", nonce, res);
    }

    @Nested
    @DisplayName("TS 33.203 AKA challenge (401)")
    class Challenge {

        @Test
        void firstRegister_issues401_withRfc3310Base64Nonce() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            StubCx cx = StubCx.okMar(RAND_AUTN, AkaMd5.toHex(XRES));
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, new RecordingBindings(), null);

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);

            SendResponse r = ra.ofType(SendResponse.class).getFirst();
            assertEquals(401, r.statusCode());
            String www = r.headers().get("WWW-Authenticate");
            assertNotNull(www);
            assertTrue(www.contains("AKAv1-MD5"), "TS 33.203 Digest algorithm");
            assertTrue(www.contains("realm=\"ims.lab\""));
            assertTrue(www.contains("nonce=\"" + NONCE + "\""),
                    "RFC 3310 §3.2: nonce = base64(RAND ‖ AUTN), not hex");
        }

        @Test
        void challenge_cachesXresFromMaa_forRemoteHssPath() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            StubCx cx = StubCx.okMar(RAND_AUTN, AkaMd5.toHex(XRES));
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, new RecordingBindings(), null);

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);

            assertTrue(hss.peekPending(CALL).isPresent() || hss.peekPending(IMPI).isPresent(),
                    "BUG: S-CSCF must cache MAA SIP-Authorization (XRES) after MAR — remote HSS leaves pending empty");
            byte[] cached = hss.peekPending(CALL).or(() -> hss.peekPending(IMPI)).map(HssLiteService.PendingAuth::xres).orElse(null);
            assertNotNull(cached);
            assertArrayEquals(XRES, cached);
            assertEquals(NONCE, hss.peekPending(CALL).orElseThrow().nonce(),
                    "the S-CSCF keeps the nonce it challenged with to verify the digest");
        }

        @Test
        void authenticate_shorterThan64Hex_500() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = StubCx.okMar("aa".repeat(31), AkaMd5.toHex(XRES)); // 62 hex
            ScscfRegisterSbb sbb = sbb(ra, cx, new RecordingHss(), new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);
            assertEquals(500, ra.ofType(SendResponse.class).getFirst().statusCode());
        }

        @Test
        void authenticate_exactly32Hex_randOnly_500() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = StubCx.okMar("aa".repeat(16), AkaMd5.toHex(XRES));
            ScscfRegisterSbb sbb = sbb(ra, cx, new RecordingHss(), new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);
            assertEquals(500, ra.ofType(SendResponse.class).getFirst().statusCode());
        }

        @Test
        void uarFail_403_noMar() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = new StubCx();
            cx.uar = Map.of(CxCodec.RESULT_CODE, "5001");
            ScscfRegisterSbb sbb = sbb(ra, cx, new RecordingHss(), new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);
            assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals(0, cx.marCalls.get());
        }

        @Test
        void marFail_403() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = new StubCx();
            cx.uar = CxCodec.uaaAvps("sip:scscf.ims.lab");
            cx.mar = Map.of(CxCodec.RESULT_CODE, "5001");
            ScscfRegisterSbb sbb = sbb(ra, cx, new RecordingHss(), new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);
            assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
        }
    }

    @Nested
    @DisplayName("TS 24.229 authenticated REGISTER (200)")
    class Authenticated {

        @Test
        void goodRes_200_serviceRouteAndPath() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, NONCE);
            RecordingBindings bindings = new RecordingBindings();
            AtomicInteger tp = new AtomicInteger();
            IscThirdPartyRegister tpReg = new IscThirdPartyRegister() {
                @Override public void notifyAs(String impu, String contact, int expires) {
                    tp.incrementAndGet();
                }
            };
            ScscfRegisterSbb sbb = sbb(ra, new StubCx(), hss, bindings, tpReg);

            String path = "<sip:pcscf.ims.lab:5060;lr>";
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    akaAuth(NONCE, XRES), path), null);

            SendResponse r = ra.ofType(SendResponse.class).getFirst();
            assertEquals(200, r.statusCode());
            assertEquals("<sip:scscf.ims.lab:5060;lr>", r.headers().get("Service-Route"),
                    "TS 24.229 Service-Route must point at S-CSCF with lr");
            assertEquals(path, r.headers().get("Path"));
            assertEquals(1, bindings.upserts.get());
            assertEquals(CONTACT, bindings.lastContact.get());
            assertEquals(path, bindings.lastPath.get(), "Path on REGISTER 200 must be stored on the binding");
            assertEquals(3600, bindings.lastExpires.get().intValue());
            assertEquals(1, tp.get());
        }

        @Test
        void truncatedRes_forbidden() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, NONCE);
            ScscfRegisterSbb sbb = sbb(ra, new StubCx(), hss, new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    akaAuth(NONCE, java.util.Arrays.copyOf(XRES, 4)), null), null);
            assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
        }

        @Test
        void emptyResponseParam_issuesChallengeNotForbidden() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            StubCx cx = StubCx.okMar(RAND_AUTN, AkaMd5.toHex(XRES));
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    "Digest response=\"\"", null), null);
            assertEquals(401, ra.ofType(SendResponse.class).getFirst().statusCode(),
                    "empty Digest response=\"\" is initial REGISTER (lab SIPp) — challenge, do not 403");
        }

        @Test
        void fullChallengeThenAuth_endToEndWithMaaCache() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            StubCx cx = StubCx.okMar(RAND_AUTN, AkaMd5.toHex(XRES));
            RecordingBindings bindings = new RecordingBindings();
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, bindings, null);

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600), null);
            assertEquals(401, ra.ofType(SendResponse.class).getFirst().statusCode());

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    akaAuth(NONCE, XRES), "<sip:pcscf;lr>"), null);
            assertEquals(200, ra.ofType(SendResponse.class).get(1).statusCode(),
                    "BUG: after 401, authenticated REGISTER must succeed when XRES was in MAA");
            assertEquals(1, bindings.upserts.get());
            assertEquals(1, cx.sarCalls.get());
        }

        @Test
        void deregister_expires0_stillRequiresAuth_thenClearsBinding() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, NONCE);
            RecordingBindings bindings = new RecordingBindings();
            ScscfRegisterSbb sbb = sbb(ra, new StubCx(), hss, bindings, null);

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 0,
                    akaAuth(NONCE, XRES), null), null);

            assertEquals(200, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals(0, bindings.lastExpires.get().intValue(),
                    "expires=0 must reach BindingStore for de-registration");
        }

        @Test
        void wrongRes_noSar_noBinding() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, NONCE);
            StubCx cx = new StubCx();
            RecordingBindings bindings = new RecordingBindings();
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, bindings, null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    akaAuth(NONCE, new byte[8]), null), null);
            assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals(0, cx.sarCalls.get());
            assertEquals(0, bindings.upserts.get());
        }

        @Test
        void resRepeatedInClear_isNotAnRfc3310Response() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, NONCE);
            ScscfRegisterSbb sbb = sbb(ra, new StubCx(), hss, new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    "Digest username=\"" + IMPI + "\", realm=\"ims.lab\", nonce=\"" + NONCE
                            + "\", uri=\"sip:ims.lab\", response=\"" + AkaMd5.toHex(XRES) + "\"", null), null);
            assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
        }
    }

    @Nested
    @DisplayName("TS 33.203 §6.1.2 synchronisation failure (auts)")
    class Resync {

        @Test
        void auts_triggersResyncMar_andAFreshChallenge() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, NONCE);
            StubCx cx = StubCx.okMar(RAND_AUTN, AkaMd5.toHex(XRES));
            RecordingBindings bindings = new RecordingBindings();
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, bindings, null);
            byte[] ki = AkaMd5.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
            byte[] opc = AkaMd5.parseHex("cd63cb71954a9f4e48a5994e37a02baf");

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    UeAka.resyncAuthorization(IMPI, "ims.lab", "sip:ims.lab", NONCE, ki, opc, 4242), null), null);

            assertEquals(401, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals(1, cx.resyncCalls.get());
            assertTrue(cx.lastRandAuts.get().startsWith(RAND), "SIP-Authorization = RAND ‖ AUTS");
            assertEquals(60, cx.lastRandAuts.get().length(), "16 + 14 octets");
            assertEquals(0, bindings.upserts.get(), "no registration on a resync round");
        }
    }

    @Nested
    @DisplayName("AKA vector crypto (TS 33.203 / Milenage lab)")
    class AkaCrypto {

        @Test
        void wwwNonce_isBase64OfRandPlusAutn() {
            byte[] ki = AkaMd5.parseHex("00112233445566778899aabbccddeeff");
            byte[] opc = AkaMd5.parseHex("000102030405060708090a0b0c0d0e0f");
            AkaMd5.Vector v = AkaMd5.generate(ki, opc, AkaMd5.parseHex("8000"), 5);
            String www = AkaMd5.wwwAuthenticate("ims.lab", v.rand(), v.autn());
            assertTrue(www.contains("nonce=\"" + AkaMd5.nonce(v.rand(), v.autn()) + "\""));
            assertEquals(16, v.rand().length);
            assertEquals(16, v.autn().length);
            assertEquals(8, v.xres().length);
            assertEquals(16, v.ck().length);
            assertEquals(16, v.ik().length);
        }

        @Test
        void emptyResponse_isNotUsable() {
            assertFalse(AkaMd5.hasResponse("Digest response=\"\""));
            assertFalse(AkaMd5.verifyResponse("Digest response=\"\"", XRES, "REGISTER", NONCE));
        }
    }

    private static ScscfRegisterSbb sbb(CapturingRa ra, CxClient cx, HssLiteService hss,
                                       BindingStore bindings, IscThirdPartyRegister tp) {
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(
                ImsTestSupport.roles(true, true, true, true), cx, hss, bindings, tp);
        ImsTestSupport.injectRa(sbb, ra);
        return sbb;
    }

    static final class StubCx extends CxClient {
        Map<Integer, String> uar = CxCodec.uaaAvps("sip:scscf.ims.lab:5060");
        Map<Integer, String> mar = Map.of(CxCodec.RESULT_CODE, "2001");
        Map<Integer, String> sar = CxCodec.saaOk();
        final AtomicInteger marCalls = new AtomicInteger();
        final AtomicInteger sarCalls = new AtomicInteger();
        final AtomicInteger resyncCalls = new AtomicInteger();
        final AtomicReference<String> lastRandAuts = new AtomicReference<>();

        static StubCx okMar(String randAutn, String xresHex) {
            StubCx cx = new StubCx();
            cx.mar = CxCodec.maaAvps(IMPI, randAutn, xresHex, "cc".repeat(16), "dd".repeat(16));
            return cx;
        }

        @Override public Map<Integer, String> uar(String impi, String impu) { return uar; }

        @Override public Map<Integer, String> mar(String impi, String impu, String callId) {
            marCalls.incrementAndGet();
            return mar;
        }

        @Override public Map<Integer, String> sar(String impi, String impu) {
            sarCalls.incrementAndGet();
            return sar;
        }

        @Override public Map<Integer, String> marResync(String impi, String impu, String callId,
                                                        String randAutsHex) {
            resyncCalls.incrementAndGet();
            lastRandAuts.set(randAutsHex);
            return mar;
        }
    }

    static final class RecordingHss extends HssLiteService {
        private final ConcurrentHashMap<String, PendingAuth> map = new ConcurrentHashMap<>();
        void seed(String callId, String impi, String impu, byte[] xres, String nonce) {
            PendingAuth p = new PendingAuth(impi, impu, xres, nonce);
            map.put(callId, p);
            map.put(impi, p);
        }

        @Override
        public void rememberChallenge(String callId, String impi, String impu, byte[] xres, String nonce) {
            PendingAuth p = new PendingAuth(impi, impu, xres, nonce);
            if (callId != null) {
                map.put(callId, p);
            }
            if (impi != null) {
                map.put(impi, p);
            }
        }

        @Override public Optional<PendingAuth> peekPending(String key) {
            return Optional.ofNullable(map.get(key));
        }

        @Override public Optional<PendingAuth> takePending(String key) {
            PendingAuth p = map.remove(key);
            if (p != null) {
                map.remove(p.impi());
            }
            return Optional.ofNullable(p);
        }

        @Override public Optional<ImsSubscriber> findByImpi(String impi) {
            return Optional.empty();
        }

        @Override public Optional<ImsSubscriber> findByImpu(String impu) {
            return Optional.empty();
        }

        @Override public Optional<ImsSubscriber> resolveIdentity(String raw) {
            return Optional.empty();
        }
    }

    static final class RecordingBindings extends BindingStore {
        final AtomicInteger upserts = new AtomicInteger();
        final AtomicReference<String> lastContact = new AtomicReference<>();
        final AtomicReference<Integer> lastExpires = new AtomicReference<>();
        final AtomicReference<String> lastPath = new AtomicReference<>();
        final AtomicReference<String> lastReceivedHost = new AtomicReference<>();
        final AtomicReference<Integer> lastReceivedPort = new AtomicReference<>();

        @Override
        public void upsert(String impu, String contact, String path, String callId, int expiresSec) {
            upsert(impu, contact, path, callId, expiresSec, null, null, null);
        }

        @Override
        public void upsert(String impu, String contact, String path, String callId, int expiresSec,
                           String receivedHost, Integer receivedPort, String receivedTransport) {
            upserts.incrementAndGet();
            lastContact.set(contact);
            lastExpires.set(expiresSec);
            lastPath.set(path);
            lastReceivedHost.set(receivedHost);
            lastReceivedPort.set(receivedPort);
        }
    }
}
