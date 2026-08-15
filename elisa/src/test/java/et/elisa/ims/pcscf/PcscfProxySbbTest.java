package et.elisa.ims.pcscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendRegister;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PcscfProxySbbTest {

    @Test
    void colocated_registerDoesNot503() {
        CapturingRa ra = new CapturingRa();
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, true, true, true));
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onRegister(new SipRegisterEvent("c1", "sip:a@ims.lab", "sip:a@ims.lab", "sip:a@1", 600));
        assertTrue(ra.commands.isEmpty(), "co-located P must not answer REGISTER when S owns it");
    }

    @Test
    void pOnly_registerMwForwardsToIcscfWithPath() {
        CapturingRa ra = new CapturingRa();
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onRegister(new SipRegisterEvent("c1", "sip:a@ims.lab", "sip:a@ims.lab", "sip:a@1", 600));
        SendRegister reg = ra.ofType(SendRegister.class).getFirst();
        assertTrue(reg.requestUri().contains("icscf"));
        assertTrue(reg.extensionHeaders().get("Path").getFirst().contains("pcscf"));
        assertTrue(reg.extensionHeaders().get("Path").getFirst().contains(";ob"),
                "RFC 5626-lite: P-CSCF Path should mark outbound");
        assertEquals(600, reg.expires());
    }

    @Test
    void pOnly_inviteStampsPaiAndRoutesToIcscf() {
        CapturingRa ra = new CapturingRa();
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onInvite(new SipInviteEvent("c2", "sip:a@ims.lab", "sip:b@ims.lab", "sip:a@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp", Map.of()));
        SendInvite inv = ra.ofType(SendInvite.class).getFirst();
        assertEquals("sip:b@ims.lab", inv.toUri());
        assertTrue(inv.nextHopUri().contains("icscf"));
        assertEquals("sip:a@ims.lab", inv.extensionHeaders().get("P-Asserted-Identity").getFirst());
        assertTrue(inv.extensionHeaders().get("Route").getFirst().contains("icscf"));
        assertTrue(inv.extensionHeaders().get("P-Charging-Vector").getFirst().contains("icid-value="));
        assertTrue(inv.extensionHeaders().containsKey("X-Elisa-Icid"));
    }

    @Test
    void colocated_inviteDoesNotDoubleSend() {
        CapturingRa ra = new CapturingRa();
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, true, true, true));
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onInvite(new SipInviteEvent("c3", "sip:a@ims.lab", "sip:b@ims.lab", "sip:a@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp"));
        assertTrue(ra.commands.isEmpty());
    }

    @Test
    void pOnly_mtInvite_usesStoredFlowNotPrivateContact() {
        CapturingRa ra = new CapturingRa();
        et.elisa.persist.ImsBinding b = new et.elisa.persist.ImsBinding();
        b.impu = "sip:alice@ims.lab";
        b.contact = "sip:alice@172.21.0.1:5060";
        b.receivedHost = "192.0.2.80";
        b.receivedPort = 5060;
        b.receivedTransport = "UDP";
        b.expiresAt = java.time.Instant.now().plusSeconds(600);
        FlowBindings bindings = new FlowBindings(b);
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false), null, bindings);
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onInvite(new SipInviteEvent("mt-nat", "sip:bob@ims.lab", "sip:alice@172.21.0.1:5060",
                "sip:bob@10.0.0.9", List.of(), List.of(), List.of(), "v=0", "application/sdp"));
        SendInvite inv = ra.ofType(SendInvite.class).getFirst();
        assertTrue(inv.nextHopUri().contains("192.0.2.80"));
        org.junit.jupiter.api.Assertions.assertFalse(inv.nextHopUri().contains("172.21.0.1"));
        org.junit.jupiter.api.Assertions.assertFalse(inv.nextHopUri().contains("icscf"));
    }

    static final class FlowBindings extends et.elisa.ims.reg.BindingStore {
        final et.elisa.persist.ImsBinding binding;

        FlowBindings(et.elisa.persist.ImsBinding binding) {
            this.binding = binding;
        }

        @Override
        public java.util.Optional<et.elisa.persist.ImsBinding> findLive(String impu) {
            return java.util.Optional.ofNullable(binding);
        }

        @Override
        public void rememberFlow(String contact, String receivedHost, Integer receivedPort,
                                 String receivedTransport) {
            // unit test — no PG
        }
    }
}
