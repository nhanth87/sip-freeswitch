package et.elisa.ims.scscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.aka.AkaMd5;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import et.elisa.ims.digest.SipDigestAuth;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.ims.support.UeAka;
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
import et.elisa.persist.ImsSubscriber;
import et.elisa.security.SipDigestHasher;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScscfRegisterSbbTest {

    /** RFC 3310 nonce the S-CSCF challenged with (base64 RAND ‖ AUTN). */
    private static final String NONCE = AkaMd5.nonce(new byte[16], new byte[16]);

    @Test
    void challenge_whenAuthorizationHasEmptyResponse() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        cx.uar = CxCodec.uaaAvps("sip:scscf.ims.lab:5060");
        cx.mar = CxCodec.maaAvps("u@ims.lab", "ab".repeat(32), "cd".repeat(8), "ef".repeat(16), "01".repeat(16));
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        // Open5GS lab SIPp uac_register_challenge.xml ships empty Digest response=""
        String emptyAuth = "Digest username=\"u@ims.lab\", realm=\"ims.lab\", uri=\"sip:ims.lab\", "
                + "nonce=\"\", response=\"\", algorithm=AKAv1-MD5";
        sbb.onRegister(new SipRegisterEvent("c-empty-auth", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1:5060", 3600, emptyAuth, null));

        SendResponse r = ra.ofType(SendResponse.class).getFirst();
        assertEquals(401, r.statusCode(),
                "empty Authorization response must challenge (401), not authenticate-fail 403");
        assertEquals(1, cx.uarCalls.get());
        assertEquals(1, cx.marCalls.get());
    }

    @Test
    void challenge_whenNoAuthorization() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        cx.uar = CxCodec.uaaAvps("sip:scscf.ims.lab:5060");
        cx.mar = CxCodec.maaAvps("u@ims.lab", "ab".repeat(32), "cd".repeat(8), "ef".repeat(16), "01".repeat(16));
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c1", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1:5060", 3600, null, null));

        SendResponse r = ra.ofType(SendResponse.class).getFirst();
        assertEquals(401, r.statusCode());
        assertTrue(r.headers().get("WWW-Authenticate").contains("AKAv1-MD5"));
        assertEquals(1, cx.uarCalls.get());
        assertEquals(1, cx.marCalls.get());
    }

    @Test
    void forbidden_whenUarFails() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        cx.uar = Map.of(CxCodec.RESULT_CODE, "5001");
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c1", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1", 3600));

        assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
        assertEquals(0, cx.marCalls.get());
    }

    @Test
    void serverError_whenAuthenticateTooShort() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        cx.uar = CxCodec.uaaAvps("sip:scscf");
        cx.mar = Map.of(CxCodec.RESULT_CODE, "2001", CxCodec.SIP_AUTHENTICATE, "aabb"); // < 32
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c1", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1", 3600));

        assertEquals(500, ra.ofType(SendResponse.class).getFirst().statusCode());
    }

    @Test
    void authenticateExactly32Hex_shouldRejectIncompleteAutn() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        cx.uar = CxCodec.uaaAvps("sip:scscf");
        // RAND only (32 hex) — AUTN missing; production check is length < 32, so this slips through
        cx.mar = Map.of(CxCodec.RESULT_CODE, "2001", CxCodec.SIP_AUTHENTICATE, "aa".repeat(16));
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c1", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1", 3600));

        SendResponse r = ra.ofType(SendResponse.class).getFirst();
        assertEquals(500, r.statusCode(),
                "BUG: RAND||AUTN must be 64 hex chars; length==32 currently issues 401 with empty AUTN");
    }

    @Test
    void success_registersBindingAndServiceRoute() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        StubHss hss = new StubHss();
        StubBindings bindings = new StubBindings();
        AtomicInteger notify = new AtomicInteger();
        byte[] xres = AkaMd5.parseHex("1122334455667788");
        hss.pending = Optional.of(new HssLiteService.PendingAuth("u@ims.lab", "sip:u@ims.lab", xres, NONCE));
        IscThirdPartyRegister tp = new IscThirdPartyRegister() {
            @Override
            public void notifyAs(String impu, String contact, int expires) {
                notify.incrementAndGet();
            }
        };
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, hss, bindings, tp);
        ImsTestSupport.injectRa(sbb, ra);

        String auth = UeAka.authorization("u@ims.lab", "ims.lab", "sip:ims.lab", NONCE, xres);
        sbb.onRegister(new SipRegisterEvent("c2", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1:5060", 3600, auth, "<sip:pcscf;lr>"));

        SendResponse r = ra.ofType(SendResponse.class).getFirst();
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().get("Service-Route").contains("scscf"));
        assertEquals("<sip:pcscf;lr>", r.headers().get("Path"));
        assertEquals(1, cx.sarCalls.get());
        assertEquals(1, bindings.upserts.get());
        assertEquals(1, notify.get());
    }

    @Test
    void success_storesReceivedFlowSeparateFromPrivateContact() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        StubHss hss = new StubHss();
        StubBindings bindings = new StubBindings();
        byte[] xres = AkaMd5.parseHex("1122334455667788");
        hss.pending = Optional.of(new HssLiteService.PendingAuth("u@ims.lab", "sip:u@ims.lab", xres, NONCE));
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, hss, bindings, null);
        ImsTestSupport.injectRa(sbb, ra);

        String auth = UeAka.authorization("u@ims.lab", "ims.lab", "sip:ims.lab", NONCE, xres);
        sbb.onRegister(new SipRegisterEvent("c-nat", "sip:alice@ims.lab", "sip:alice@ims.lab",
                "sip:alice@172.21.0.1:5060", 3600, auth, "<sip:pcscf.ims.lab:5060;lr;ob>",
                "192.0.2.80", 5060, "UDP"));

        assertEquals(200, ra.ofType(SendResponse.class).getFirst().statusCode());
        assertEquals(1, bindings.upserts.get());
        assertEquals("192.0.2.80", bindings.lastReceivedHost.get());
        assertEquals(5060, bindings.lastReceivedPort.get().intValue());
    }

    @Test
    void digestRegister_dashboardSipClient() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx();
        cx.uar = CxCodec.uaaAvps("sip:scscf.ims.lab:5060");
        DigestHss hss = new DigestHss();
        StubBindings bindings = new StubBindings();
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, hss, bindings, null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c-dig", "sip:alice@ims.lab", "sip:alice@ims.lab",
                "sip:alice@10.0.0.8:5060", 3600, null, null));
        SendResponse challenge = ra.ofType(SendResponse.class).getFirst();
        assertEquals(401, challenge.statusCode());
        assertTrue(challenge.headers().get("WWW-Authenticate").contains("algorithm=MD5"));
        assertEquals(0, cx.marCalls.get());

        String nonce = SipDigestAuth.nonceOf(challenge.headers().get("WWW-Authenticate"));
        String ha1 = hss.sub.fsA1Hash;
        String uri = "sip:ims.lab";
        String ha2 = SipDigestAuth.md5Hex("REGISTER:" + uri);
        String response = SipDigestAuth.md5Hex(ha1 + ":" + nonce + ":" + ha2);
        String auth = "Digest username=\"alice\", realm=\"ims.lab\", nonce=\"" + nonce
                + "\", uri=\"" + uri + "\", response=\"" + response + "\", algorithm=MD5";
        sbb.onRegister(new SipRegisterEvent("c-dig", "sip:alice@ims.lab", "sip:alice@ims.lab",
                "sip:alice@10.0.0.8:5060", 3600, auth, "<sip:pcscf;lr>"));
        SendResponse ok = ra.ofType(SendResponse.class).get(1);
        assertEquals(200, ok.statusCode());
        assertEquals(1, bindings.upserts.get());
        assertEquals(1, cx.sarCalls.get());
    }

    @Test
    void badRes_forbidden() {
        CapturingRa ra = new CapturingRa();
        StubHss hss = new StubHss();
        hss.pending = Optional.of(new HssLiteService.PendingAuth("u@ims.lab", "sip:u@ims.lab",
                AkaMd5.parseHex("1122334455667788"), NONCE));
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                new StubCx(), hss, new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c3", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1", 3600,
                UeAka.authorization("u@ims.lab", "ims.lab", "sip:ims.lab", NONCE, new byte[8]), null));

        assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
    }

    @Test
    void roleOff_sendsNothing() {
        CapturingRa ra = new CapturingRa();
        ImsRoleFlags roles = ImsTestSupport.roles(true, true, false, true);
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(roles, new StubCx(), new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onRegister(new SipRegisterEvent("c4", "sip:u@ims.lab", "sip:u@ims.lab", "sip:u@1", 3600));
        assertTrue(ra.commands.isEmpty());
    }

    @Test
    void npe_whenCxNullButScscfEnabled() {
        CapturingRa ra = new CapturingRa();
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                null, new StubHss(), new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);
        try {
            sbb.onRegister(new SipRegisterEvent("c5", "sip:u@ims.lab", "sip:u@ims.lab", "sip:u@1", 3600));
            org.junit.jupiter.api.Assertions.fail("BUG: expected NPE when cx is null");
        } catch (NullPointerException expected) {
            // documents bootstrap must never leave cx null when scscf=true
        }
    }

    static final class StubCx extends CxClient {
        Map<Integer, String> uar = Map.of(CxCodec.RESULT_CODE, "2001");
        Map<Integer, String> mar = Map.of(CxCodec.RESULT_CODE, "2001");
        Map<Integer, String> sar = CxCodec.saaOk();
        final AtomicInteger uarCalls = new AtomicInteger();
        final AtomicInteger marCalls = new AtomicInteger();
        final AtomicInteger sarCalls = new AtomicInteger();

        @Override public Map<Integer, String> uar(String impi, String impu) {
            uarCalls.incrementAndGet();
            return uar;
        }

        @Override public Map<Integer, String> mar(String impi, String impu, String callId) {
            marCalls.incrementAndGet();
            return mar;
        }

        @Override public Map<Integer, String> sar(String impi, String impu) {
            sarCalls.incrementAndGet();
            return sar;
        }
    }

    static class StubHss extends HssLiteService {
        Optional<HssLiteService.PendingAuth> pending = Optional.empty();

        @Override public Optional<PendingAuth> peekPending(String key) {
            return pending;
        }

        @Override public Optional<PendingAuth> takePending(String key) {
            return pending;
        }

        @Override public Optional<et.elisa.persist.ImsSubscriber> findByImpi(String impi) {
            return Optional.empty();
        }

        @Override public Optional<et.elisa.persist.ImsSubscriber> findByImpu(String impu) {
            return Optional.empty();
        }

        @Override public Optional<et.elisa.persist.ImsSubscriber> resolveIdentity(String raw) {
            return Optional.empty();
        }
    }

    static final class DigestHss extends StubHss {
        final ImsSubscriber sub = new ImsSubscriber();

        DigestHss() {
            sub.impi = "alice@ims.lab";
            sub.impu = "sip:alice@ims.lab";
            sub.fsA1Hash = SipDigestHasher.a1Hash("alice", "ims.lab", "alice");
            sub.enabled = true;
        }

        @Override
        public Optional<ImsSubscriber> findByImpi(String impi) {
            return Optional.of(sub);
        }

        @Override
        public Optional<ImsSubscriber> findByImpu(String impu) {
            return Optional.of(sub);
        }

        @Override
        public Optional<ImsSubscriber> resolveIdentity(String raw) {
            return Optional.of(sub);
        }
    }

    static final class StubBindings extends BindingStore {
        final AtomicInteger upserts = new AtomicInteger();
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
            lastReceivedHost.set(receivedHost);
            lastReceivedPort.set(receivedPort);
        }
    }
}
