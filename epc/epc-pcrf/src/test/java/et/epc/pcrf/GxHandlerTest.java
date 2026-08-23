package et.epc.pcrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.QosProfile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GxHandlerTest {

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

    @Test
    void parsesCcrFromFlatAvps() {
        Map<Integer, String> in = new HashMap<>();
        in.put(GxHandler.SUBSCRIPTION_ID_DATA, "001010000000001");
        in.put(GxHandler.CALLED_STATION_ID, "ims");
        in.put(GxHandler.FRAMED_IP_ADDRESS, "10.65.0.2");
        in.put(GxHandler.CHARGING_ID, "42");
        in.put(GxHandler.CC_REQUEST_TYPE, GxHandler.REQ_TYPE_INITIAL);
        in.put(GxHandler.CC_REQUEST_NUMBER, "1");

        GxHandler.GxCcr ccr = GxHandler.GxCcr.parse(in, "gx-session-1");
        assertEquals("gx-session-1", ccr.sessionId());
        assertEquals("001010000000001", ccr.imsi());
        assertEquals("ims", ccr.apn());
        assertEquals("10.65.0.2", ccr.framedIp());
        assertEquals(42L, ccr.chargingId());
        assertEquals("1", ccr.requestType());
        assertEquals(1L, ccr.ccRequestNumber());
    }

    @Test
    void ccaEncodesInstallDefaultBearerAndUsageMonitor() {
        GxHandler handler = new GxHandler(pcrfWith(List.of(PccRule.imsSignalQci5())));
        Map<Integer, String> in = new HashMap<>();
        in.put(GxHandler.SUBSCRIPTION_ID_DATA, "001010000000001");
        in.put(GxHandler.CALLED_STATION_ID, "ims");
        Map<Integer, String> cca = handler.handleCcr(in, "gx-a");

        assertEquals(GxHandler.RESULT_SUCCESS, cca.get(GxHandler.RESULT_CODE));
        String install = cca.get(GxHandler.CHARGING_RULE_INSTALL);
        assertTrue(install.contains("name=ims-signal-qci5"));
        assertTrue(install.contains("qci=5"));
        String qos = cca.get(GxHandler.DEFAULT_EPS_BEARER_QOS);
        assertTrue(qos.contains("qci=5"));
        String um = cca.get(GxHandler.USAGE_MONITORING_INFORMATION);
        assertTrue(um.contains("key=pilot-volume"));
        assertTrue(um.contains("threshold=1000000000"));
    }

    @Test
    void missingImsiFailsClosedWithUserUnknown() {
        GxHandler handler = new GxHandler(pcrfWith(List.of(PccRule.imsSignalQci5())));
        Map<Integer, String> cca = handler.handleCcr(Map.of(), "gx-missing");
        assertEquals(GxHandler.RESULT_USER_UNKNOWN, cca.get(GxHandler.RESULT_CODE));
    }

    @Test
    void encodeRuleIsDeterministic() {
        String s = GxHandler.encodeRule(PccRule.imsVoiceQci1());
        assertEquals("name=ims-voice-qci1;qci=1;arp=1,1,0;mbr=512000000,512000000;"
                + "gbr=512000000,512000000;ratingGroup=-;serviceId=ims-rtp;"
                + "flow=permit out udp from any to any;gating=true", s);
    }

    @Test
    void decisionJsonRoundTripsExpectedFragments() {
        PccDecision d = new PccDecision(List.of(PccRule.imsSignalQci5()), 1_000_000_000L,
                et.epc.api.QosProfile.pilotDefault());
        String json = GxHandler.toDecisionJson(d);
        assertTrue(json.startsWith("{\"rules\":[\"name=ims-signal-qci5"));
        assertTrue(json.contains("\"usageMonitorThreshold\":1000000000"));
    }

    @Test
    void ccaStructuredEmitsGroupedRuleFlowQosAndDefaultBearer() {
        PccRule rule = new PccRule("rule-a", 9, 8, false, true,
                1_000_000_000L, 1_000_000_000L, 0L, 0L,
                7, "100", "permit out ip from any to any", true);
        QosProfile defaultQos = QosProfile.pilotDefault();

        GxHandler handler = new GxHandler(pcrfWith(List.of(rule)));
        List<DiameterAvp> body = handler.ccaStructured(
                new GxHandler.GxCcr("gx-1", "001010000000001", "ims", "10.65.0.2", 42L, "1", 1L),
                new PccDecision(List.of(rule), null, defaultQos));

        DiameterAvp install = child(body, GxHandler.CHARGING_RULE_INSTALL);
        assertNotNull(install);
        assertEquals(GxHandler.VENDOR_3GPP, install.vendorId());
        assertEquals(DiameterAvp.Type.GROUPED, install.type());

        DiameterAvp definition = child(install.children(), GxHandler.CHARGING_RULE_DEFINITION);
        assertNotNull(definition);
        assertEquals(GxHandler.VENDOR_3GPP, definition.vendorId());

        DiameterAvp qosInfo = child(definition.children(), GxHandler.QOS_INFORMATION);
        assertNotNull(qosInfo);
        DiameterAvp qci = child(qosInfo.children(), GxHandler.QOS_CLASS_IDENTIFIER);
        assertNotNull(qci);
        assertEquals(rule.qci(), qci.asUnsigned32());

        DiameterAvp flowInfo = child(definition.children(), GxHandler.FLOW_INFORMATION);
        assertNotNull(flowInfo);
        DiameterAvp flowDesc = child(flowInfo.children(), GxHandler.FLOW_DESCRIPTION);
        assertNotNull(flowDesc);
        assertEquals("permit out ip from any to any",
                new String(flowDesc.asOctets(), StandardCharsets.UTF_8));

        DiameterAvp flowStatus = child(flowInfo.children(), GxHandler.FLOW_STATUS);
        assertNotNull(flowStatus, "Flow-Information must carry Flow-Status(511)");
        assertEquals(GxHandler.VENDOR_3GPP, flowStatus.vendorId());
        assertEquals(1L, flowStatus.asUnsigned32());

        // QoS-Information: QCI + ARP + MBR/GBR leaves.
        DiameterAvp arp = child(qosInfo.children(), GxHandler.ALLOCATION_RETENTION_PRIORITY);
        assertNotNull(arp);
        assertEquals(rule.arpPriority(),
                child(arp.children(), GxHandler.PRIORITY_LEVEL).asUnsigned32());
        assertEquals(rule.mbrUlBps(),
                child(qosInfo.children(), GxHandler.MAX_REQUESTED_BANDWIDTH_UL).asUnsigned32());
        assertEquals(rule.mbrDlBps(),
                child(qosInfo.children(), GxHandler.MAX_REQUESTED_BANDWIDTH_DL).asUnsigned32());
        assertEquals(rule.gbrUlBps(),
                child(qosInfo.children(), GxHandler.GUARANTEED_BITRATE_UL).asUnsigned32());
        assertEquals(rule.gbrDlBps(),
                child(qosInfo.children(), GxHandler.GUARANTEED_BITRATE_DL).asUnsigned32());

        assertNotNull(child(body, GxHandler.DEFAULT_EPS_BEARER_QOS));
        assertEquals(defaultQos.qci(), child(child(body, GxHandler.DEFAULT_EPS_BEARER_QOS).children(),
                GxHandler.QOS_CLASS_IDENTIFIER).asUnsigned32());

        assertEquals(1L, child(body, GxHandler.AUTH_SESSION_STATE).asUnsigned32(),
                "CCA must carry Auth-Session-State(277)=NO_STATE_MAINTAINED");
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