package et.elisa.ims.pcscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.ims.ImsSipHeaderNames;
import et.elisa.ims.charging.PChargingVectors;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.support.ImsTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PcscfChargingVectorTest {

    @Test
    void invite_preservesExistingPChargingVector() {
        ImsRoleFlags roles = ImsTestSupport.roles(true, false, false, false);
        PcscfProxySbb sbb = new PcscfProxySbb(roles);
        ImsTestSupport.CapturingRa ra = new ImsTestSupport.CapturingRa();
        ImsTestSupport.injectRa(sbb, ra);

        Map<String, List<String>> ims = Map.of(
                ImsSipHeaderNames.P_CHARGING_VECTOR,
                List.of("icid-value=from-ue;orig-ioi=ue.net"));
        SipInviteEvent invite = new SipInviteEvent(
                "cid-1", "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:alice@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp", ims);

        sbb.onInvite(invite);

        List<SendInvite> out = ra.ofType(SendInvite.class);
        assertEquals(1, out.size());
        Map<String, List<String>> hdrs = out.getFirst().extensionHeaders();
        assertEquals("icid-value=from-ue;orig-ioi=ue.net",
                hdrs.get(PChargingVectors.HEADER).getFirst());
        assertTrue(hdrs.containsKey(PChargingVectors.X_ELISA_ICID));
        assertEquals("from-ue", hdrs.get(PChargingVectors.X_ELISA_ICID).getFirst());
    }

    @Test
    void invite_mintsWhenMissing() {
        ImsRoleFlags roles = ImsTestSupport.roles(true, false, false, false);
        PcscfProxySbb sbb = new PcscfProxySbb(roles);
        ImsTestSupport.CapturingRa ra = new ImsTestSupport.CapturingRa();
        ImsTestSupport.injectRa(sbb, ra);

        SipInviteEvent invite = new SipInviteEvent(
                "cid-mint", "sip:alice@ims.lab", "sip:bob@ims.lab", "sip:alice@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp", Map.of());

        sbb.onInvite(invite);

        SendInvite out = ra.ofType(SendInvite.class).getFirst();
        String expected = PChargingVectors.synthesizeIcid("cid-mint");
        assertEquals(expected, out.extensionHeaders().get(PChargingVectors.X_ELISA_ICID).getFirst());
        assertTrue(out.extensionHeaders().get(PChargingVectors.HEADER).getFirst()
                .contains("icid-value=" + expected));
    }
}
