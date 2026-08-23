package et.epc.pcrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RxHandlerTest {

    private static RxHandler rx() {
        PcrfService pcrf = new PcrfService(new HeapPccCache());
        return new RxHandler(pcrf);
    }

    @Test
    void classifiesVoiceAsQci1AndSignalAsQci5() {
        assertEquals(1, RxHandler.qciFor("audio rtp"));
        assertEquals(1, RxHandler.qciFor("voice"));
        assertEquals(5, RxHandler.qciFor("sip"));
        assertEquals(5, RxHandler.qciFor(""));
        assertEquals(5, RxHandler.qciFor(null));
    }

    @Test
    void installMapsVoiceToQci1ChargingRuleInstall() {
        Map<Integer, String> gx = rx().installRules("af-1", "10.65.0.2", "audio");
        assertTrue(gx.containsKey(GxHandler.CHARGING_RULE_INSTALL));
        assertTrue(gx.get(GxHandler.CHARGING_RULE_INSTALL).contains("name=ims-voice-qci1"));
        assertTrue(gx.get(GxHandler.CHARGING_RULE_INSTALL).contains("qci=1"));
    }

    @Test
    void aarBindsAndReturnsSuccess() {
        RxHandler h = rx();
        Map<Integer, String> aar = new HashMap<>();
        aar.put(RxHandler.FRAMED_IP_ADDRESS, "10.65.0.2");
        aar.put(RxHandler.MEDIA_TYPE, "audio");
        aar.put(RxHandler.AF_APPLICATION_IDENTIFIER, "ims-pcscf");
        Map<Integer, String> aaa = h.handleAar(aar, "af-1");
        assertEquals(GxHandler.RESULT_SUCCESS, aaa.get(RxHandler.RESULT_CODE));
        assertEquals("ims-pcscf", aaa.get(RxHandler.AF_APPLICATION_IDENTIFIER));
        RxHandler.AfSession s = h.session("af-1");
        assertEquals(RxHandler.STATE_BOUND, s.state());
        assertEquals("ims-voice-qci1", s.ruleName());
    }

    @Test
    void strTerminatesAndRemovesRule() {
        RxHandler h = rx();
        h.installRules("af-2", "10.65.0.3", "audio");
        Map<Integer, String> sta = h.handleStr(Map.of(), "af-2");
        assertEquals(GxHandler.RESULT_SUCCESS, sta.get(RxHandler.RESULT_CODE));
        assertEquals(RxHandler.STATE_TERMINATED, h.session("af-2").state());
        Map<Integer, String> remove = h.removeRule("af-2");
        assertTrue(remove.containsKey(GxHandler.CHARGING_RULE_REMOVE));
        assertEquals("ims-voice-qci1", remove.get(GxHandler.CHARGING_RULE_REMOVE));
    }

    @Test
    void unknownAfSessionRemoveReturnsEmpty() {
        assertTrue(rx().removeRule("missing").isEmpty());
    }

    @Test
    void rarAndAsrBuildOutboundRequestAvps() {
        RxHandler h = rx();
        Map<Integer, String> rar = h.buildRar("af-9");
        assertEquals(RxHandler.RE_AUTH_REQUEST_TYPE_AUTHORIZE_ONLY, rar.get(RxHandler.RE_AUTH_REQUEST_TYPE));

        Map<Integer, String> asr = h.buildAsr("af-9", "1");
        assertEquals("1", asr.get(RxHandler.ABORT_CAUSE));
        assertEquals("0", h.buildAsr("af-9", null).get(RxHandler.ABORT_CAUSE));
    }

    @Test
    void aaaStructuredEmitsIpCanTypeAndRatType() {
        RxHandler h = rx();
        h.installRules("af-10", "10.65.0.4", "audio");
        List<DiameterAvp> body = h.aaaStructured(new RxHandler.AfSession(
                "af-10", "10.65.0.4", "audio", "ims-voice-qci1", RxHandler.STATE_BOUND));

        DiameterAvp ipCan = child(body, GxHandler.IP_CAN_TYPE);
        assertEquals(GxHandler.VENDOR_3GPP, ipCan.vendorId());
        assertEquals(3L, ipCan.asUnsigned32());

        DiameterAvp rat = child(body, GxHandler.RAT_TYPE);
        assertEquals(GxHandler.VENDOR_3GPP, rat.vendorId());
        assertEquals(1004L, rat.asUnsigned32());

        DiameterAvp authState = child(body, GxHandler.AUTH_SESSION_STATE);
        assertEquals(1L, authState.asUnsigned32(), "AAA must carry Auth-Session-State(277)");
    }

    private static DiameterAvp child(List<DiameterAvp> avps, int code) {
        for (DiameterAvp a : avps) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }
}