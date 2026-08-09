package et.elisa.ims.pcscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendResponse;
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
    void pOnly_registerReturns503WithPath() {
        CapturingRa ra = new CapturingRa();
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onRegister(new SipRegisterEvent("c1", "sip:a@ims.lab", "sip:a@ims.lab", "sip:a@1", 600));
        SendResponse r = ra.ofType(SendResponse.class).getFirst();
        assertEquals(503, r.statusCode());
        assertTrue(r.headers().get("Path").contains("pcscf"));
    }

    @Test
    void pOnly_inviteStampsPaiAndRoutesToIcscf() {
        CapturingRa ra = new CapturingRa();
        PcscfProxySbb sbb = new PcscfProxySbb(ImsTestSupport.roles(true, false, false, false));
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onInvite(new SipInviteEvent("c2", "sip:a@ims.lab", "sip:b@ims.lab", "sip:a@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp", Map.of()));
        SendInvite inv = ra.ofType(SendInvite.class).getFirst();
        assertTrue(inv.toUri().contains("icscf"));
        assertEquals("sip:a@ims.lab", inv.extensionHeaders().get("P-Asserted-Identity").getFirst());
        assertTrue(inv.extensionHeaders().get("Route").getFirst().contains("icscf"));
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
}
