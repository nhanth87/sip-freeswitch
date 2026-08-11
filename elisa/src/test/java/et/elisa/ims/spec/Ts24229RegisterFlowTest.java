package et.elisa.ims.spec;

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
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
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

    @Nested
    @DisplayName("TS 33.203 AKA challenge (401)")
    class Challenge {

        @Test
        void firstRegister_issues401_withAkav1Md5Nonce64Hex() {
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
            assertTrue(www.contains("nonce=\"" + RAND_AUTN + "\""),
                    "nonce MUST be RAND||AUTN (32+32 hex)");
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
            assertTrue(AkaMd5.verifyRes(XRES, cached));
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
            hss.seed(CALL, IMPI, IMPU, XRES, 42);
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
                    "Digest username=\"" + IMPI + "\", realm=\"ims.lab\", response=\""
                            + AkaMd5.toHex(XRES) + "\"",
                    path), null);

            SendResponse r = ra.ofType(SendResponse.class).getFirst();
            assertEquals(200, r.statusCode());
            assertEquals("<sip:scscf.ims.lab:5060;lr>", r.headers().get("Service-Route"),
                    "TS 24.229 Service-Route must point at S-CSCF with lr");
            assertEquals(path, r.headers().get("Path"));
            assertEquals(1, bindings.upserts.get());
            assertEquals(CONTACT, bindings.lastContact.get());
            assertEquals(3600, bindings.lastExpires.get().intValue());
            assertEquals(42L, hss.committedSqn.get().longValue());
            assertEquals(1, tp.get());
        }

        @Test
        void truncatedRes_forbidden() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, 3);
            ScscfRegisterSbb sbb = sbb(ra, new StubCx(), hss, new RecordingBindings(), null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    "Digest response=\"" + AkaMd5.toHex(XRES).substring(0, 8) + "\"", null), null);
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
                    "Digest response=\"" + AkaMd5.toHex(XRES) + "\"",
                    "<sip:pcscf;lr>"), null);
            assertEquals(200, ra.ofType(SendResponse.class).get(1).statusCode(),
                    "BUG: after 401, authenticated REGISTER must succeed when XRES was in MAA");
            assertEquals(1, bindings.upserts.get());
            assertEquals(1, cx.sarCalls.get());
        }

        @Test
        void deregister_expires0_stillRequiresAuth_thenClearsBinding() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, 9);
            RecordingBindings bindings = new RecordingBindings();
            ScscfRegisterSbb sbb = sbb(ra, new StubCx(), hss, bindings, null);

            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 0,
                    "Digest response=\"" + AkaMd5.toHex(XRES) + "\"", null), null);

            assertEquals(200, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals(0, bindings.lastExpires.get().intValue(),
                    "expires=0 must reach BindingStore for de-registration");
        }

        @Test
        void wrongRes_noSar_noBinding() {
            CapturingRa ra = new CapturingRa();
            RecordingHss hss = new RecordingHss();
            hss.seed(CALL, IMPI, IMPU, XRES, 2);
            StubCx cx = new StubCx();
            RecordingBindings bindings = new RecordingBindings();
            ScscfRegisterSbb sbb = sbb(ra, cx, hss, bindings, null);
            sbb.onEvent(new SipRegisterEvent(CALL, "sip:" + IMPI, IMPU, CONTACT, 3600,
                    "Digest response=\"0000000000000000\"", null), null);
            assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals(0, cx.sarCalls.get());
            assertEquals(0, bindings.upserts.get());
        }
    }

    @Nested
    @DisplayName("AKA vector crypto (TS 33.203 / Milenage lab)")
    class AkaCrypto {

        @Test
        void wwwNonce_isExactlyRandPlusAutn() {
            byte[] ki = AkaMd5.parseHex("00112233445566778899aabbccddeeff");
            byte[] opc = AkaMd5.parseHex("000102030405060708090a0b0c0d0e0f");
            AkaMd5.Vector v = AkaMd5.generate(ki, opc, AkaMd5.parseHex("8000"), 5);
            String www = AkaMd5.wwwAuthenticate("ims.lab", v.rand(), v.autn());
            String expect = AkaMd5.toHex(v.rand()) + AkaMd5.toHex(v.autn());
            assertEquals(64, expect.length());
            assertTrue(www.contains("nonce=\"" + expect + "\""));
            assertEquals(16, v.rand().length);
            assertEquals(16, v.autn().length);
            assertEquals(8, v.xres().length);
            assertEquals(16, v.ck().length);
            assertEquals(16, v.ik().length);
        }

        @Test
        void extractRes_emptyQuotes_mustNotYieldSixteenZeros() {
            byte[] got = AkaMd5.extractResFromAuthorization("Digest response=\"\"");
            assertTrue(got == null || got.length == 0,
                    "BUG: empty response=\"\" currently becomes 16 zero bytes via parseHex blank");
            assertFalse(AkaMd5.verifyRes(XRES, got));
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
    }

    static final class RecordingHss extends HssLiteService {
        private final ConcurrentHashMap<String, PendingAuth> map = new ConcurrentHashMap<>();
        final AtomicReference<Long> committedSqn = new AtomicReference<>();

        void seed(String callId, String impi, String impu, byte[] xres, long nextSqn) {
            PendingAuth p = new PendingAuth(impi, impu, xres, nextSqn);
            map.put(callId, p);
            map.put(impi, p);
        }

        @Override
        public void rememberChallenge(String callId, String impi, String impu, byte[] xres, long nextSqn) {
            PendingAuth p = new PendingAuth(impi, impu, xres, nextSqn);
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

        @Override public void commitSqn(String impi, long nextSqn) {
            committedSqn.set(nextSqn);
        }
    }

    static final class RecordingBindings extends BindingStore {
        final AtomicInteger upserts = new AtomicInteger();
        final AtomicReference<String> lastContact = new AtomicReference<>();
        final AtomicReference<Integer> lastExpires = new AtomicReference<>();

        @Override
        public void upsert(String impu, String contact, String path, String callId, int expiresSec) {
            upserts.incrementAndGet();
            lastContact.set(contact);
            lastExpires.set(expiresSec);
        }
    }
}
