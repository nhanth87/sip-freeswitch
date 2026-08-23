package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Imsi;
import et.epc.api.QosProfile;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PcefServiceGxPathTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private static PccRule rule(String name) {
        return new PccRule(name, 5, 1, true, false,
                1_000_000_000L, 1_000_000_000L, 0L, 0L,
                7, "ims-sip", "permit out ip from any to any", true);
    }

    @Test
    void staticFallbackWhenPcrfNotLive() {
        PcefService pcef = new PcefService();
        PcrfClient spy = (imsi, apn, ueIpv4, chargingId, requestType) ->
                PolicyDecision.of(QosProfile.pilotDefault(), true, "from-client", List.of(rule("r")));
        pcef.setPcrfClient(spy);
        pcef.setPcrfLive(false);

        assertEquals("ims-qci5", pcef.authorize(IMSI, "ims").ruleName());
        assertEquals("static-default", pcef.authorize("internet").ruleName());
        assertEquals("static-default",
                pcef.beginGxSession(IMSI, "internet", "10.64.0.2", 7L, "INITIAL_REQUEST").ruleName());
    }

    @Test
    void gxPathDelegatesToInjectedClientWhenLive() {
        PcefService pcef = new PcefService();
        AtomicLong lastChargingId = new AtomicLong();
        PcrfClient client = (imsi, apn, ueIpv4, chargingId, requestType) -> {
            lastChargingId.set(chargingId);
            return PolicyDecision.of(QosProfile.pilotDefault(), true, "from-client", List.of(rule("r1")));
        };
        pcef.setPcrfClient(client);
        pcef.setPcrfLive(true);

        PolicyDecision d = pcef.beginGxSession(IMSI, "internet", "10.64.0.2", 77L, "INITIAL_REQUEST");
        assertEquals("from-client", d.ruleName());
        assertEquals(1, d.rules().size());
        assertEquals("r1", d.rules().get(0).name());
        assertEquals(77L, lastChargingId.get());

        assertSame(client, pcef.pcrfClient());
        assertTrue(pcef.pcrfLive());
    }

    @Test
    void authorizeImsiConsultsClientWhenLive() {
        PcefService pcef = new PcefService();
        pcef.setPcrfClient((imsi, apn, u, c, r) ->
                PolicyDecision.of(QosProfile.pilotDefault(), true, "live-" + apn, List.of()));
        pcef.setPcrfLive(true);
        assertEquals("live-ims", pcef.authorize(IMSI, "ims").ruleName());
    }

    @Test
    void policyDecisionCarriesInstalledRules() {
        List<PccRule> rules = List.of(rule("a"), rule("b"));
        PolicyDecision d = PolicyDecision.of(QosProfile.pilotDefault(), true, "n", rules);
        assertEquals(2, d.rules().size());
        assertEquals("a", d.rules().get(0).name());
        // 3-arg constructor keeps empty rule list.
        assertTrue(new PolicyDecision(QosProfile.pilotDefault(), true, "x").rules().isEmpty());
    }

    @Test
    void pcrfClientDefaultIsNull() {
        PcefService pcef = new PcefService();
        assertNull(pcef.pcrfClient());
        assertFalse(pcef.pcrfLive());
    }
}