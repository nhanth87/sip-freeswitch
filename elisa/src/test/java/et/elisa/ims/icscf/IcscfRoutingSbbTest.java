package et.elisa.ims.icscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.ims.support.ImsTestSupport.CapturingRa;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IcscfRoutingSbbTest {

    @Test
    void register_uarFail_403() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx(Map.of(CxCodec.RESULT_CODE, "5001"), Map.of());
        IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onRegister(new SipRegisterEvent("c1", "sip:u@ims.lab", "sip:u@ims.lab", "sip:u@1", 3600));
        assertEquals(403, ra.ofType(SendResponse.class).getFirst().statusCode());
    }

    @Test
    void register_iOnly_503WhenNoScscf() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx(CxCodec.uaaAvps("sip:scscf.ims.lab"), Map.of());
        IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onRegister(new SipRegisterEvent("c1", "sip:u@ims.lab", "sip:u@ims.lab", "sip:u@1", 3600));
        assertEquals(503, ra.ofType(SendResponse.class).getFirst().statusCode());
    }

    @Test
    void invite_lirOk_forwardsToScscfWhenIOnly() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx(Map.of(), CxCodec.liaAvps("sip:scscf.ims.lab:5060"));
        IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onInvite(new SipInviteEvent("c2", "sip:a@ims.lab", "sip:b@ims.lab", "sip:a@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp"));
        SendInvite inv = ra.ofType(SendInvite.class).getFirst();
        assertTrue(inv.toUri().contains("scscf"));
    }

    @Test
    void invite_lirFail_404() {
        CapturingRa ra = new CapturingRa();
        StubCx cx = new StubCx(Map.of(), Map.of(CxCodec.RESULT_CODE, "5001"));
        IcscfRoutingSbb sbb = new IcscfRoutingSbb(ImsTestSupport.roles(false, true, false, false), cx);
        ImsTestSupport.injectRa(sbb, ra);
        sbb.onInvite(new SipInviteEvent("c2", "sip:a@ims.lab", "sip:b@ims.lab", "sip:a@1",
                List.of(), List.of(), List.of(), "v=0", "application/sdp"));
        assertEquals(404, ra.ofType(SendResponse.class).getFirst().statusCode());
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
}
