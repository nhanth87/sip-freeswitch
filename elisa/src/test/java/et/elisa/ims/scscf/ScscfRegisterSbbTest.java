package et.elisa.ims.scscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.aka.AkaMd5;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScscfRegisterSbbTest {

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
        hss.pending = Optional.of(new HssLiteService.PendingAuth("u@ims.lab", "sip:u@ims.lab", xres, 9));
        IscThirdPartyRegister tp = new IscThirdPartyRegister() {
            @Override
            public void notifyAs(String impu, String contact, int expires) {
                notify.incrementAndGet();
            }
        };
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                cx, hss, bindings, tp);
        ImsTestSupport.injectRa(sbb, ra);

        String auth = "Digest response=\"" + AkaMd5.toHex(xres) + "\"";
        sbb.onRegister(new SipRegisterEvent("c2", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1:5060", 3600, auth, "<sip:pcscf;lr>"));

        SendResponse r = ra.ofType(SendResponse.class).getFirst();
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().get("Service-Route").contains("scscf"));
        assertEquals("<sip:pcscf;lr>", r.headers().get("Path"));
        assertEquals(1, cx.sarCalls.get());
        assertEquals(1, bindings.upserts.get());
        assertEquals(1, notify.get());
        assertEquals(9L, hss.committedSqn.get().longValue());
    }

    @Test
    void badRes_forbidden() {
        CapturingRa ra = new CapturingRa();
        StubHss hss = new StubHss();
        hss.pending = Optional.of(new HssLiteService.PendingAuth("u@ims.lab", "sip:u@ims.lab",
                AkaMd5.parseHex("1122334455667788"), 2));
        ScscfRegisterSbb sbb = new ScscfRegisterSbb(ImsTestSupport.roles(true, true, true, true),
                new StubCx(), hss, new StubBindings(), null);
        ImsTestSupport.injectRa(sbb, ra);

        sbb.onRegister(new SipRegisterEvent("c3", "sip:u@ims.lab", "sip:u@ims.lab",
                "sip:u@10.0.0.1", 3600, "Digest response=\"0000000000000000\"", null));

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

    static final class StubHss extends HssLiteService {
        Optional<HssLiteService.PendingAuth> pending = Optional.empty();
        final AtomicReference<Long> committedSqn = new AtomicReference<>();

        @Override public Optional<PendingAuth> peekPending(String key) {
            return pending;
        }

        @Override public Optional<PendingAuth> takePending(String key) {
            return pending;
        }

        @Override public void commitSqn(String impi, long nextSqn) {
            committedSqn.set(nextSqn);
        }
    }

    static final class StubBindings extends BindingStore {
        final AtomicInteger upserts = new AtomicInteger();

        @Override
        public void upsert(String impu, String contact, String path, String callId, int expiresSec) {
            upserts.incrementAndGet();
        }
    }
}
