package et.epc.pgwc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import et.epc.api.Imsi;
import et.epc.pcef.PccRule;
import et.epc.pcef.PolicyDecision;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Gx wire seam: CCR encode / CCA parse roundtrip over a fake diameter-ra
 * command port. No socket, no peer — wire claims stay out of scope.
 */
class WirePcrfClientTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final String PCRF_HOST = "epc-pcrf-1.epc.lab";

    /** Records outbound CCRs and optionally answers synchronously. */
    private static final class FakeDiameterPort implements RaCommandPort {
        final List<SendDiameterRequest> requests = new ArrayList<>();
        final Function<SendDiameterRequest, DiameterAnswerEvent> responder;
        WirePcrfClient client;

        FakeDiameterPort(Function<SendDiameterRequest, DiameterAnswerEvent> responder) {
            this.responder = responder;
        }

        @Override
        public void sendCommand(OutboundCommand command) {
            SendDiameterRequest req = (SendDiameterRequest) command;
            requests.add(req);
            DiameterAnswerEvent answer = responder == null ? null : responder.apply(req);
            // Answers are events now: tests hand them to WirePcrfClient#decision.
        }
    }

    private static GxWireConfig wireConfig(long timeoutMs) {
        return new GxWireConfig(true, PCRF_HOST, 3868, timeoutMs, "epc.lab");
    }

    private static WirePcrfClient client(FakeDiameterPort port, boolean peerReady, long timeoutMs) {
        WirePcrfClient client = new WirePcrfClient(port, () -> peerReady, wireConfig(timeoutMs));
        port.client = client;
        return client;
    }

    private static DiameterAnswerEvent cca(String sessionId, long rc,
                                           Map<Integer, String> avps,
                                           List<DiameterAvp> structured) {
        return new DiameterAnswerEvent(sessionId, WirePcrfClient.GX_APP_ID,
                WirePcrfClient.CCR_CMD, rc, 7L, 9L, PCRF_HOST, "epc.lab", avps, structured);
    }

    /** Mirrors et.epc.pcrf.GxHandler.encodeRule (flat contract). */
    private static String encodeRule(String name, int qci, int arpP, boolean cap, boolean vuln,
                                     long mbrUl, long mbrDl, long gbrUl, long gbrDl,
                                     Integer ratingGroup, String serviceId,
                                     String flow, boolean gating) {
        return "name=" + name + ";qci=" + qci
                + ";arp=" + arpP + "," + (cap ? 1 : 0) + "," + (vuln ? 1 : 0)
                + ";mbr=" + mbrUl + "," + mbrDl
                + ";gbr=" + gbrUl + "," + gbrDl
                + ";ratingGroup=" + (ratingGroup == null ? "-" : ratingGroup)
                + ";serviceId=" + (serviceId == null ? "-" : serviceId)
                + ";flow=" + (flow == null ? "" : flow)
                + ";gating=" + gating;
    }

    private static Map<Integer, String> successCcaAvps(String rules) {
        Map<Integer, String> avps = new LinkedHashMap<>();
        avps.put(WirePcrfClient.RESULT_CODE, "2001");
        if (rules != null) {
            avps.put(WirePcrfClient.CHARGING_RULE_INSTALL, rules);
        }
        avps.put(WirePcrfClient.DEFAULT_EPS_BEARER_QOS,
                "qci=5;arp=1,1,0;apnAmbr=1000000000,1000000000;ueAmbr=2000000000,2000000000");
        return avps;
    }

    private static DiameterAvp child(List<DiameterAvp> avps, int code) {
        return avps.stream().filter(a -> a.code() == code).findFirst().orElse(null);
    }

    /** TS 29.212 §5.6.2: typed CCR-I, nothing in the string map; no wait on the CCA. */
    @Test
    void ccrIsTypedAndSentWithoutWaiting() {
        FakeDiameterPort port = new FakeDiameterPort(null);
        WirePcrfClient client = client(port, true, 2_000L);

        String sid = client.sendCcr(IMSI, "internet", "10.64.0.2", 0x2a, 1, 0).orElseThrow();

        assertEquals(1, port.requests.size());
        SendDiameterRequest ccr = port.requests.get(0);
        assertEquals(sid, ccr.sessionId());
        assertTrue(ccr.avps().isEmpty(), "no string-encoded AVPs");
        List<DiameterAvp> t = ccr.avpsStructured();
        assertEquals(WirePcrfClient.GX_APP_ID, child(t, 258).asUnsigned32());
        assertEquals(DiameterAvp.Type.ENUMERATED32, child(t, 416).type());
        assertEquals(1L, child(t, 416).asUnsigned32());
        DiameterAvp sub = child(t, 443);
        assertEquals(DiameterAvp.Type.GROUPED, sub.type());
        assertEquals(1L, child(sub.children(), 450).asUnsigned32());
        assertEquals(IMSI.digits(), child(sub.children(), 444).asUtf8());
        assertArrayEquals(new byte[] {10, 64, 0, 2}, child(t, 8).asOctets());
        assertEquals(10415L, child(t, 1027).vendorId());
        assertEquals(5L, child(t, 1027).asUnsigned32());
        assertEquals(1004L, child(t, 1032).asUnsigned32());
        assertEquals("internet", child(t, 30).asUtf8());
        assertEquals(sid, client.sessionIdFor(0x2a), "Session-Id derives from the Charging-Id");
    }

    @Test
    void ccrTerminationCarriesNoUeAddress() {
        FakeDiameterPort port = new FakeDiameterPort(null);
        WirePcrfClient client = client(port, true, 2_000L);
        client.sendCcr(IMSI, "internet", null, 0x2a, 3, 1).orElseThrow();
        List<DiameterAvp> t = port.requests.get(0).avpsStructured();
        assertEquals(3L, child(t, 416).asUnsigned32());
        assertEquals(1L, child(t, 415).asUnsigned32());
        assertEquals(null, child(t, 8));
    }

    @Test
    void peerNotReadyOrInProcessSendsNothing() {
        FakeDiameterPort port = new FakeDiameterPort(null);
        assertTrue(client(port, false, 2_000L).sendCcr(IMSI, "internet", "10.64.0.2", 1, 1, 0).isEmpty());
        WirePcrfClient inProc = new WirePcrfClient(port,
                () -> true, new GxWireConfig(false, PCRF_HOST, 3868, 2_000L, "epc.lab"));
        assertTrue(inProc.sendCcr(IMSI, "internet", "10.64.0.2", 1, 1, 0).isEmpty());
        assertTrue(port.requests.isEmpty());
        // The synchronous seam never waits: static policy at once.
        assertEquals("static-default", inProc.beginGxSession(IMSI, "internet", null, 0L, null).ruleName());
    }

    /** Typed CCA: Default-EPS-Bearer-QoS (1049) and command-level APN-AMBR become the policy. */
    @Test
    void typedCcaBecomesThePolicy() {
        WirePcrfClient client = client(new FakeDiameterPort(null), true, 2_000L);
        DiameterAvp dq = DiameterAvp.grouped3gpp(1049, List.of(
                DiameterAvp.v3gpp(1028, 6L),
                DiameterAvp.grouped3gpp(1034, List.of(
                        DiameterAvp.u32(1046, 10415L, 2L, true),
                        DiameterAvp.v3gpp(1047, 0L),       // ENABLED
                        DiameterAvp.v3gpp(1048, 1L)))));   // DISABLED
        DiameterAvp ambr = DiameterAvp.grouped3gpp(1016, List.of(
                DiameterAvp.u32(1041, 10415L, 50_000_000L, true),
                DiameterAvp.u32(1040, 10415L, 100_000_000L, true)));
        PolicyDecision d = client.decision("internet", cca("s", 2001L, Map.of(), List.of(dq, ambr)));
        assertEquals(6, d.qos().qci());
        assertEquals(2, d.qos().arpPriority());
        assertTrue(d.qos().preemptionCapability());
        assertFalse(d.qos().preemptionVulnerability());
        assertEquals(50_000_000L, d.qos().apnAmbrUlBps());
        assertEquals(100_000_000L, d.qos().apnAmbrDlBps());
    }

    @Test
    void refusedCcaIsNoPolicy() {
        WirePcrfClient client = client(new FakeDiameterPort(null), true, 2_000L);
        assertEquals(null, client.decision("internet", cca("s", 5030L, Map.of(), List.of())));
    }

    @Test
    void structuredChargingRuleInstallParsesWhenFlatMapEmpty() {
        DiameterAvp definition = DiameterAvp.grouped3gpp(
                WirePcrfClient.CHARGING_RULE_DEFINITION, List.of(
                        DiameterAvp.utf8(WirePcrfClient.CHARGING_RULE_NAME, 10415L,
                                "ims-voice-qci1", true),
                        DiameterAvp.grouped3gpp(WirePcrfClient.QOS_INFORMATION, List.of(
                                DiameterAvp.v3gpp(WirePcrfClient.QOS_CLASS_IDENTIFIER, 1L),
                                DiameterAvp.u32(WirePcrfClient.MAX_REQUESTED_BANDWIDTH_UL,
                                        10415L, 512_000_000L, true),
                                DiameterAvp.u32(WirePcrfClient.MAX_REQUESTED_BANDWIDTH_DL,
                                        10415L, 512_000_000L, true),
                                DiameterAvp.u32(WirePcrfClient.GUARANTEED_BITRATE_UL,
                                        10415L, 256_000_000L, true),
                                DiameterAvp.u32(WirePcrfClient.GUARANTEED_BITRATE_DL,
                                        10415L, 256_000_000L, true),
                                DiameterAvp.grouped3gpp(
                                        WirePcrfClient.ALLOCATION_RETENTION_PRIORITY, List.of(
                                                DiameterAvp.u32(WirePcrfClient.PRIORITY_LEVEL,
                                                        10415L, 1L, true),
                                                DiameterAvp.v3gpp(          // 0 = ENABLED
                                                        WirePcrfClient.PRE_EMPTION_CAPABILITY, 0L),
                                                DiameterAvp.v3gpp(          // 1 = DISABLED
                                                        WirePcrfClient.PRE_EMPTION_VULNERABILITY,
                                                        1L))))),
                        DiameterAvp.grouped3gpp(WirePcrfClient.FLOW_INFORMATION, List.of(
                                DiameterAvp.octets(WirePcrfClient.FLOW_DESCRIPTION, 10415L,
                                        "permit out udp from any to any"
                                                .getBytes(StandardCharsets.UTF_8), true),
                                DiameterAvp.v3gpp(WirePcrfClient.FLOW_STATUS, 2L)))));   // ENABLED
        DiameterAvp install = DiameterAvp.grouped3gpp(
                WirePcrfClient.CHARGING_RULE_INSTALL, List.of(definition));
        FakeDiameterPort port = new FakeDiameterPort(req ->
                cca(req.sessionId(), 2001L, Map.of(WirePcrfClient.RESULT_CODE, "2001"),
                        List.of(install)));
        WirePcrfClient client = client(port, true, 2_000L);

        PolicyDecision decision = client.decision("ims",
                cca("s", 2001L, Map.of(WirePcrfClient.RESULT_CODE, "2001"), List.of(install)));

        assertEquals(1, decision.rules().size());
        PccRule rule = decision.rules().get(0);
        assertEquals("ims-voice-qci1", rule.name());
        assertEquals(1, rule.qci());
        assertEquals(512_000_000L, rule.mbrUlBps());
        assertEquals(256_000_000L, rule.gbrUlBps());
        assertEquals(1, rule.arpPriority());
        assertTrue(rule.preemptionCapable());
        assertFalse(rule.preemptionVulnerable());
        assertEquals("permit out udp from any to any", rule.flowDescription());
        assertTrue(rule.gatingOpen());
    }

    @Test
    void configFromEnvParsesWireKnobs() {
        System.setProperty("epc.pcrf.wire.mode", "wire");
        System.setProperty("epc.pcrf.peer.host", PCRF_HOST);
        System.setProperty("epc.pcrf.peer.port", "3869");
        System.setProperty("epc.pcrf.ccr.timeout-ms", "1234");
        try {
            GxWireConfig config = GxWireConfig.fromEnv();
            assertTrue(config.wire());
            assertEquals(PCRF_HOST, config.peerHost());
            assertEquals(3869, config.peerPort());
            assertEquals(1234L, config.ccrTimeoutMs());
            assertEquals("epc.lab", config.destRealm());
        } finally {
            System.clearProperty("epc.pcrf.wire.mode");
            System.clearProperty("epc.pcrf.peer.host");
            System.clearProperty("epc.pcrf.peer.port");
            System.clearProperty("epc.pcrf.ccr.timeout-ms");
        }
        // default stays in-process
        assertFalse(GxWireConfig.fromEnv().wire());
    }
}