package et.epc.pgwc;

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
            if (answer != null && client != null) {
                client.onCca(answer);
            }
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

    @Test
    void ccrEncodesFlatAvpsAndCcaRoundTripsIntoPolicyDecision() {
        FakeDiameterPort port = new FakeDiameterPort(req ->
                cca(req.sessionId(), 2001L, successCcaAvps(encodeRule(
                        "ims-voice-qci1", 1, 1, true, false,
                        512_000_000L, 512_000_000L, 256_000_000L, 256_000_000L,
                        7, "ims-rtp", "permit out udp from any to any", true)), List.of()));
        WirePcrfClient client = client(port, true, 2_000L);

        PolicyDecision decision = client.beginGxSession(IMSI, "ims", "10.65.0.2", 42L, "1");

        assertEquals(1, port.requests.size());
        SendDiameterRequest ccr = port.requests.get(0);
        assertEquals(WirePcrfClient.GX_APP_ID, ccr.applicationId());
        assertEquals(WirePcrfClient.CCR_CMD, ccr.commandCode());
        assertEquals(PCRF_HOST, ccr.destinationHost());
        assertEquals("epc.lab", ccr.destinationRealm());
        assertTrue(ccr.sessionId().startsWith("epc-pgwc-1.epc.lab;gx-wire;"));
        Map<Integer, String> body = ccr.avps();
        assertEquals(IMSI.digits(), body.get(WirePcrfClient.SUBSCRIPTION_ID_DATA));
        assertEquals("1", body.get(WirePcrfClient.SUBSCRIPTION_ID_TYPE));
        assertEquals("ims", body.get(WirePcrfClient.CALLED_STATION_ID));
        assertEquals("10.65.0.2", body.get(WirePcrfClient.FRAMED_IP_ADDRESS));
        assertEquals("42", body.get(WirePcrfClient.CHARGING_ID));
        assertEquals("1", body.get(WirePcrfClient.CC_REQUEST_TYPE));
        assertEquals("1", body.get(WirePcrfClient.AUTH_SESSION_STATE));

        assertEquals(1, decision.rules().size());
        PccRule rule = decision.rules().get(0);
        assertEquals("ims-voice-qci1", rule.name());
        assertEquals("ims-voice-qci1", decision.ruleName());
        assertEquals(1, rule.qci());
        assertEquals(1, rule.arpPriority());
        assertTrue(rule.preemptionCapable());
        assertFalse(rule.preemptionVulnerable());
        assertEquals(512_000_000L, rule.mbrUlBps());
        assertEquals(256_000_000L, rule.gbrDlBps());
        assertEquals(7, rule.ratingGroup());
        assertEquals("ims-rtp", rule.serviceId());
        assertEquals("permit out udp from any to any", rule.flowDescription());
        assertTrue(decision.gatingOpen());
        assertEquals(5, decision.qos().qci());
        assertEquals(1, decision.qos().arpPriority());
        assertEquals(2_000_000_000L, decision.qos().ueAmbrDlBps());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void peerNotReadyFallsBackStaticAndSendsNothing() {
        FakeDiameterPort port = new FakeDiameterPort(null);
        WirePcrfClient client = client(port, false, 2_000L);

        PolicyDecision decision = client.beginGxSession(IMSI, "internet", "10.64.0.2", 1L, null);

        assertTrue(port.requests.isEmpty());
        assertEquals("static-default", decision.ruleName());
        assertEquals("ims-qci5",
                client.beginGxSession(IMSI, "ims", null, 0L, null).ruleName());
        assertEquals(0, client.pendingCount());
    }

    @Test
    void inProcessModeFallsBackEvenWhenPeerReady() {
        FakeDiameterPort port = new FakeDiameterPort(null);
        WirePcrfClient client = new WirePcrfClient(port, () -> true, GxWireConfig.inProcess());
        port.client = client;

        PolicyDecision decision = client.beginGxSession(IMSI, "internet", null, 0L, null);

        assertTrue(port.requests.isEmpty());
        assertEquals("static-default", decision.ruleName());
        assertFalse(client.wireEnabled());
    }

    @Test
    void ccaTimeoutFallsBackStaticAndClearsPending() {
        FakeDiameterPort port = new FakeDiameterPort(null); // never answers
        WirePcrfClient client = client(port, true, 50L);

        long start = System.nanoTime();
        PolicyDecision decision = client.beginGxSession(IMSI, "internet", null, 0L, null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(1, port.requests.size());
        assertEquals("static-default", decision.ruleName());
        assertEquals(0, client.pendingCount());
        assertTrue(elapsedMs >= 40, "expected the CCA wait, took " + elapsedMs + "ms");
    }

    @Test
    void nonSuccessCcaFallsBackStatic() {
        FakeDiameterPort port = new FakeDiameterPort(req ->
                cca(req.sessionId(), 5001L,
                        Map.of(WirePcrfClient.RESULT_CODE, "5001"), List.of()));
        WirePcrfClient client = client(port, true, 2_000L);

        PolicyDecision decision = client.beginGxSession(IMSI, "internet", null, 0L, null);

        assertEquals("static-default", decision.ruleName());
        assertEquals(1, port.requests.size());
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
                                                DiameterAvp.v3gpp(
                                                        WirePcrfClient.PRE_EMPTION_CAPABILITY, 1L),
                                                DiameterAvp.v3gpp(
                                                        WirePcrfClient.PRE_EMPTION_VULNERABILITY,
                                                        0L))))),
                        DiameterAvp.grouped3gpp(WirePcrfClient.FLOW_INFORMATION, List.of(
                                DiameterAvp.octets(WirePcrfClient.FLOW_DESCRIPTION, 10415L,
                                        "permit out udp from any to any"
                                                .getBytes(StandardCharsets.UTF_8), true),
                                DiameterAvp.v3gpp(WirePcrfClient.FLOW_STATUS, 1L)))));
        DiameterAvp install = DiameterAvp.grouped3gpp(
                WirePcrfClient.CHARGING_RULE_INSTALL, List.of(definition));
        FakeDiameterPort port = new FakeDiameterPort(req ->
                cca(req.sessionId(), 2001L, Map.of(WirePcrfClient.RESULT_CODE, "2001"),
                        List.of(install)));
        WirePcrfClient client = client(port, true, 2_000L);

        PolicyDecision decision = client.beginGxSession(IMSI, "ims", null, 0L, null);

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