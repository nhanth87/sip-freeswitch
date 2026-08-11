package et.elisa.ims.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendRegister;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import com.microjainslee.ra.sipservlet.events.SipResponseEvent;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.icscf.IcscfRoutingSbb;
import et.elisa.ims.pcscf.PcscfProxySbb;
import et.elisa.ims.reg.BindingStore;
import et.elisa.ims.scscf.IscAsRouter;
import et.elisa.ims.scscf.ScscfInviteSbb;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
import et.elisa.persist.ImsBinding;
import et.elisa.persist.ImsSubscriber;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * TS 24.229 Gm/Mw INVITE routing — P/I/S CSCF, PAI, ISC, MT via Path/contact.
 */
class Ts24229InviteRoutingTest {

    @Nested
    @DisplayName("P-CSCF (Gm)")
    class Pcscf {

        @Test
        void pOnly_moInvite_stampsPaiAndRouteToIcscf() {
            CapturingRa ra = new CapturingRa();
            PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(invite("c1", "sip:a@ims.lab", "sip:b@ims.lab"), null);
            SendInvite inv = ra.ofType(SendInvite.class).getFirst();
            assertEquals("sip:b@ims.lab", inv.toUri());
            assertEquals("sip:icscf.ims.lab:5060", inv.nextHopUri());
            assertEquals("sip:a@ims.lab", inv.extensionHeaders().get("P-Asserted-Identity").getFirst());
            assertTrue(inv.extensionHeaders().get("Route").getFirst().contains("icscf"));
        }

        @Test
        void pOnly_preservesExistingPani() {
            CapturingRa ra = new CapturingRa();
            PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
            ImsTestSupport.injectRa(sbb, ra);
            Map<String, List<String>> ims = Map.of(
                    "P-Access-Network-Info", List.of("3GPP-E-UTRAN;utran-cell-id-3gpp=..."));
            sbb.onEvent(new SipInviteEvent("c1", "sip:a@ims.lab", "sip:b@ims.lab", "sip:a@1",
                    List.of(), List.of(), List.of(), "v=0", "application/sdp", ims), null);
            SendInvite inv = ra.ofType(SendInvite.class).getFirst();
            assertTrue(inv.extensionHeaders().containsKey("P-Asserted-Identity"));
            assertEquals("3GPP-E-UTRAN;utran-cell-id-3gpp=...",
                    inv.extensionHeaders().get("P-Access-Network-Info").getFirst(),
                    "TS 24.229: P-CSCF must preserve P-Access-Network-Info toward core");
        }

        @Test
        void colocated_doesNotAnswerRegisterOrInvite() {
            CapturingRa ra = new CapturingRa();
            PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, true, true, true));
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(new SipRegisterEvent("r", "sip:a@ims.lab", "sip:a@ims.lab", "sip:a@1", 600), null);
            sbb.onEvent(invite("i", "sip:a@ims.lab", "sip:b@ims.lab"), null);
            assertTrue(ra.commands.isEmpty());
        }

        @Test
        void pOnly_responsePassthrough() {
            CapturingRa ra = new CapturingRa();
            PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(new SipResponseEvent("c1", 183, "Session Progress", "v=0", "application/sdp", List.of()), null);
            assertEquals(183, ra.ofType(SendResponse.class).getFirst().statusCode());
        }
    }

    @Nested
    @DisplayName("I-CSCF (Cx UAR/LIR)")
    class Icscf {

        @Test
        void mtInvite_lirOk_forwardsToHomeScscf() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = new StubCx(Map.of(), CxCodec.liaAvps("sip:scscf-home.ims.lab:5060"));
            IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(invite("mt1", "sip:ext@other", "sip:bob@ims.lab"), null);
            SendInvite inv = ra.ofType(SendInvite.class).getFirst();
            assertEquals("sip:bob@ims.lab", inv.toUri());
            assertTrue(inv.nextHopUri().contains("scscf-home"));
        }

        @Test
        void mtInvite_lirMissingUser_404() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = new StubCx(Map.of(), Map.of(CxCodec.RESULT_CODE, "5001"));
            IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(invite("mt2", "sip:ext@other", "sip:unknown@ims.lab"), null);
            assertEquals(404, ra.ofType(SendResponse.class).getFirst().statusCode());
        }

        @Test
        void register_uarOk_iOnly_forwardsMwRegisterToScscf() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = new StubCx(CxCodec.uaaAvps("sip:scscf.ims.lab"), Map.of());
            IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(new SipRegisterEvent("r1", "sip:a@ims.lab", "sip:a@ims.lab", "sip:a@1", 3600), null);
            assertTrue(ra.ofType(SendResponse.class).isEmpty(), "I-only must not answer REGISTER after UAR OK");
            SendRegister fwd = ra.ofType(SendRegister.class).getFirst();
            assertEquals("sip:scscf.ims.lab", fwd.requestUri());
            assertEquals("r1", fwd.callId());
        }

        @Test
        void colocated_invite_doesNotDoubleForward() {
            CapturingRa ra = new CapturingRa();
            StubCx cx = new StubCx(Map.of(), CxCodec.liaAvps("sip:scscf.ims.lab"));
            IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, true, false), cx);
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(invite("c", "sip:a@ims.lab", "sip:b@ims.lab"), null);
            assertTrue(ra.ofType(SendInvite.class).isEmpty(),
                    "co-located S owns INVITE — I must not SendInvite");
        }
    }

    @Nested
    @DisplayName("S-CSCF session (ISC / MT)")
    class Scscf {

        @Test
        void moInvite_defaultIfc_routesToAsWithPaiAndRoute() {
            CapturingRa ra = new CapturingRa();
            IscAsRouter isc = router("sip:as@127.0.0.1:5062");
            ScscfInviteSbb sbb = new ScscfInviteSbb.$Concrete(
                    ImsTestSupport.roles(false, false, true, false),
                    isc, new EmptyBindings(), new DialogCmpPersistence().withoutPg(),
                    null, null, "elisa-1", "fs-1");
            ImsTestSupport.injectRa(sbb, ra);

            sbb.onEvent(invite("mo1", "sip:alice@ims.lab", "sip:bob@ims.lab"), null);

            SendInvite inv = ra.ofType(SendInvite.class).getFirst();
            assertEquals("sip:as@127.0.0.1:5062", inv.toUri());
            assertEquals("sip:alice@ims.lab", inv.extensionHeaders().get("P-Asserted-Identity").getFirst());
            assertTrue(inv.extensionHeaders().get("Route").getFirst().contains("as@127.0.0.1"));
            assertEquals("mo1", inv.extensionHeaders().get("X-Elisa-Sip-Call-Id").getFirst());
        }

        @Test
        void moInvite_ifcSkipAs_mustNotStillForceAsUri() {
            CapturingRa ra = new CapturingRa();
            IscAsRouter isc = router("sip:as@127.0.0.1:5062");
            StubHss hss = new StubHss();
            hss.ifcByImpu.put("sip:alice@ims.lab", "{\"skip\":true}");
            ScscfInviteSbb sbb = new ScscfInviteSbb.$Concrete(
                    ImsTestSupport.roles(false, false, true, true),
                    isc, new EmptyBindings(), new DialogCmpPersistence().withoutPg(),
                    null, hss, "elisa-1", "fs-1");
            ImsTestSupport.injectRa(sbb, ra);

            sbb.onEvent(invite("mo-skip", "sip:alice@ims.lab", "sip:bob@ims.lab"), null);

            SendInvite inv = ra.ofType(SendInvite.class).getFirst();
            assertFalse(inv.toUri().contains("as@127.0.0.1"),
                    "BUG: shouldInvokeAs(false) still targets isc.asUri() — iFC skip is a no-op");
            assertEquals("sip:bob@ims.lab", inv.toUri());
        }

        @Test
        void mtInvite_usesRegisteredContact_notAs() {
            CapturingRa ra = new CapturingRa();
            ImsBinding b = new ImsBinding();
            b.impu = "sip:bob@ims.lab";
            b.contact = "sip:bob@10.0.0.9:5060";
            b.expiresAt = Instant.now().plusSeconds(600);
            b.pathHeader = "<sip:pcscf;lr>";
            LiveBindings bindings = new LiveBindings(b);
            ScscfInviteSbb sbb = new ScscfInviteSbb.$Concrete(
                    ImsTestSupport.roles(false, false, true, false),
                    router("sip:as@fs"), bindings, new DialogCmpPersistence().withoutPg(),
                    null, null, "elisa-1", "fs-1");
            ImsTestSupport.injectRa(sbb, ra);

            sbb.onEvent(invite("mt", "sip:alice@ims.lab", "sip:bob@ims.lab"), null);

            SendInvite inv = ra.ofType(SendInvite.class).getFirst();
            assertEquals("sip:bob@10.0.0.9:5060", inv.toUri());
            assertFalse(inv.extensionHeaders().containsKey("Route")
                    && inv.extensionHeaders().get("Route").getFirst().contains("as@fs"));
        }

        @Test
        void provisional183_forwardedAndCmpEarly() {
            CapturingRa ra = new CapturingRa();
            DialogCmpPersistence store = new DialogCmpPersistence().withoutPg();
            ScscfInviteSbb sbb = new ScscfInviteSbb.$Concrete(
                    ImsTestSupport.roles(false, false, true, false),
                    router("sip:as@fs"), new EmptyBindings(), store,
                    null, null, "elisa-1", "fs-1");
            ImsTestSupport.injectRa(sbb, ra);
            sbb.onEvent(invite("early", "sip:a@ims.lab", "sip:b@ims.lab"), null);
            sbb.onEvent(new SipResponseEvent("early", 183, "Session Progress", "v=0", "application/sdp", List.of()), null);
            assertEquals(183, ra.ofType(SendResponse.class).getFirst().statusCode());
            assertEquals("EARLY", sbb.getDialogState());
        }
    }

    private static SipInviteEvent invite(String callId, String from, String to) {
        return new SipInviteEvent(callId, from, to, from,
                List.of(), List.of(), List.of(), "v=0\r\no=- 0 0 IN IP4 10.0.0.1", "application/sdp");
    }

    private static IscAsRouter router(String as) {
        try {
            var roles = ImsTestSupport.roles(true, true, true, true);
            ImsTestSupport.set(roles, "iscAsUri", as);
            IscAsRouter r = new IscAsRouter();
            ImsTestSupport.set(r, "roles", roles);
            ImsTestSupport.set(r, "trunks", null);
            ImsTestSupport.set(r, "fallbackTrunk", "sip:fallback@127.0.0.1:5062");
            ImsTestSupport.set(r, "bgcfUri", "");
            return r;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static final class StubCx extends CxClient {
        final Map<Integer, String> uar;
        final Map<Integer, String> lir;

        StubCx(Map<Integer, String> uar, Map<Integer, String> lir) {
            this.uar = uar;
            this.lir = lir;
        }

        @Override public Map<Integer, String> uar(String impi, String impu) { return uar; }
        @Override public Map<Integer, String> lir(String impu) { return lir; }
    }

    static final class EmptyBindings extends BindingStore {
        @Override public Optional<ImsBinding> findLive(String impu) { return Optional.empty(); }
    }

    static final class LiveBindings extends BindingStore {
        final ImsBinding binding;

        LiveBindings(ImsBinding binding) {
            this.binding = binding;
        }

        @Override public Optional<ImsBinding> findLive(String impu) {
            return Optional.ofNullable(binding);
        }
    }

    static final class StubHss extends HssLiteService {
        final Map<String, String> ifcByImpu = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<ImsSubscriber> findByImpu(String impu) {
            String ifc = ifcByImpu.get(impu);
            if (ifc == null) {
                return Optional.empty();
            }
            ImsSubscriber s = new ImsSubscriber();
            s.impu = impu;
            s.ifcJson = ifc;
            return Optional.of(s);
        }

        @Override
        public Optional<ImsSubscriber> findByImpi(String impi) {
            return Optional.empty();
        }

        @Override
        public Optional<ImsSubscriber> findByMsisdn(String msisdn) {
            return Optional.empty();
        }

        @Override
        public Optional<ImsSubscriber> resolveIdentity(String raw) {
            return findByImpu(raw == null ? "" : raw.trim());
        }
    }
}
