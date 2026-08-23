package et.epc.pcrf.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.pcrf.GxHandler;
import et.epc.pcrf.HeapPccCache;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.PolicyRepository;
import et.epc.pcrf.RxHandler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PCRF Gx/Rx Diameter gateway SBB demux + procedure glue, driven without a container. */
class PcrfSbbTest {

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort recordingPort = sent::add;
    private PcrfSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        sbb = new PcrfSbb.$Concrete(pcrfWith(List.of(PccRule.imsSignalQci5())));
        inject(sbb, "diameterRa", recordingPort);
    }

    private static PcrfService pcrfWith(List<PccRule> rules) {
        PolicyRepository repo = new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                return rules;
            }
            @Override public void upsertIpCanSession(String s, String i, String a, String u, long c, String j) {
            }
        };
        return new PcrfService(new HeapPccCache(repo), repo);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = PcrfSbb.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object read(Object target, String name) throws Exception {
        Field f = PcrfSbb.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static DiameterRequestEvent req(long appId, int cc, Map<Integer, String> avps) {
        return new DiameterRequestEvent("sess-1", appId, cc, 9L, 10L,
                "pcef.host", "epc.mno", "pcrf.epc", "epc.lab", avps);
    }

    private static DiameterRequestEvent reqStructured(long appId, int cc,
                                                      Map<Integer, String> avps,
                                                      List<DiameterAvp> structured) {
        return new DiameterRequestEvent("sess-1", appId, cc, 9L, 10L,
                "pcef.host", "epc.mno", "pcrf.epc", "epc.lab", avps, structured);
    }

    private SendDiameterAnswer last() {
        assertFalse(sent.isEmpty(), "expected at least one answer");
        return (SendDiameterAnswer) sent.get(sent.size() - 1);
    }

    private RxHandler rx() throws Exception {
        return (RxHandler) read(sbb, "rx");
    }

    @Test
    void ccrAnswersCcaWithChargingRuleInstallAndDefaultBearerQos() {
        Map<Integer, String> ccr = Map.of(
                GxHandler.SUBSCRIPTION_ID_DATA, "001010000000001",
                GxHandler.CALLED_STATION_ID, "ims",
                GxHandler.FRAMED_IP_ADDRESS, "10.65.0.2",
                GxHandler.CHARGING_ID, "42",
                GxHandler.CC_REQUEST_TYPE, GxHandler.REQ_TYPE_INITIAL,
                GxHandler.CC_REQUEST_NUMBER, "1");

        sbb.onEvent(req(PcrfSbb.GX, GxHandler.CCR_CMD, ccr), null);

        SendDiameterAnswer ans = last();
        assertEquals(PcrfSbb.GX, ans.applicationId());
        assertEquals(GxHandler.CCR_CMD, ans.commandCode());
        assertEquals(2001L, ans.resultCode());

        String install = ans.avps().get(GxHandler.CHARGING_RULE_INSTALL);
        assertNotNull(install);
        assertTrue(install.contains("name=ims-signal-qci5"));
        assertTrue(install.contains("qci=5"));

        String qos = ans.avps().get(GxHandler.DEFAULT_EPS_BEARER_QOS);
        assertNotNull(qos);
        assertTrue(qos.contains("qci=5"));

        assertTrue(ans.avps().get(GxHandler.USAGE_MONITORING_INFORMATION)
                .contains("key=pilot-volume"));

        assertFalse(ans.avpsStructured().isEmpty());

        DiameterAvp installAvp = child(ans.avpsStructured(), GxHandler.CHARGING_RULE_INSTALL);
        assertNotNull(installAvp, "CCA must carry grouped Charging-Rule-Install(1001)");
        DiameterAvp definition = child(children(installAvp), GxHandler.CHARGING_RULE_DEFINITION);
        assertNotNull(definition, "install must carry grouped Charging-Rule-Definition(1003)");
        DiameterAvp sessionState = child(ans.avpsStructured(), GxHandler.AUTH_SESSION_STATE);
        assertNotNull(sessionState, "CCA must carry Auth-Session-State(277)");
        assertEquals(1L, sessionState.asUnsigned32());
    }

    @Test
    void ccrReadsImsiAndApnFromStructuredSubscriptionIdWhenFlatMapLacksThem() {
        List<DiameterAvp> structured = List.of(
                DiameterAvp.grouped3gpp(443, List.of(
                        DiameterAvp.utf8(GxHandler.SUBSCRIPTION_ID_DATA, GxHandler.VENDOR_3GPP,
                                "001010000000001", true))),
                DiameterAvp.utf8(GxHandler.CALLED_STATION_ID, "ims"));

        sbb.onEvent(reqStructured(PcrfSbb.GX, GxHandler.CCR_CMD, Map.of(), structured), null);

        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        String install = ans.avps().get(GxHandler.CHARGING_RULE_INSTALL);
        assertNotNull(install);
        assertTrue(install.contains("name=ims-signal-qci5"));
        assertFalse(ans.avpsStructured().isEmpty());
    }

    @Test
    void aarAnswersAaaAndInstallsRxRule() throws Exception {
        Map<Integer, String> aar = Map.of(
                RxHandler.FRAMED_IP_ADDRESS, "10.65.0.2",
                RxHandler.MEDIA_TYPE, "audio",
                RxHandler.AF_APPLICATION_IDENTIFIER, "ims-pcscf");

        sbb.onEvent(req(PcrfSbb.RX, RxHandler.AA_CMD, aar), null);

        SendDiameterAnswer ans = last();
        assertEquals(PcrfSbb.RX, ans.applicationId());
        assertEquals(2001L, ans.resultCode());
        assertEquals("ims-pcscf", ans.avps().get(RxHandler.AF_APPLICATION_IDENTIFIER));

        RxHandler.AfSession session = rx().session("sess-1");
        assertNotNull(session);
        assertEquals(RxHandler.STATE_BOUND, session.state());
        assertEquals("ims-voice-qci1", session.ruleName());
    }

    @Test
    void strAnswersStaAndTerminatesRxSession() throws Exception {
        Map<Integer, String> aar = Map.of(
                RxHandler.FRAMED_IP_ADDRESS, "10.65.0.2",
                RxHandler.MEDIA_TYPE, "audio");
        sbb.onEvent(req(PcrfSbb.RX, RxHandler.AA_CMD, aar), null);
        sent.clear();

        sbb.onEvent(req(PcrfSbb.RX, RxHandler.ST_CMD, Map.of()), null);

        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        assertEquals(RxHandler.STATE_TERMINATED, rx().session("sess-1").state());
    }

    @Test
    void cachesSessionAndApplicationOnCmp() {
        sbb.onEvent(req(PcrfSbb.GX, GxHandler.CCR_CMD, Map.of(
                GxHandler.SUBSCRIPTION_ID_DATA, "001010000000001",
                GxHandler.CALLED_STATION_ID, "ims")), null);
        assertEquals("sess-1", sbb.getSessionId());
        assertEquals((Long) PcrfSbb.GX, sbb.getApplicationId());
    }

    private static DiameterAvp child(List<DiameterAvp> avps, int code) {
        for (DiameterAvp a : avps) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }

    private static List<DiameterAvp> children(DiameterAvp avp) {
        return avp.children() == null ? List.of() : avp.children();
    }
}