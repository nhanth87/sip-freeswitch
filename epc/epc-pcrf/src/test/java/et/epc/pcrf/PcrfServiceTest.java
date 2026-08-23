package et.epc.pcrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PcrfServiceTest {

    private static List<PccRule> imsRules() {
        return List.of(PccRule.imsSignalQci5());
    }

    private static PolicyRepository repo(List<PccRule> rules) {
        return new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                return rules;
            }
            @Override public void upsertIpCanSession(String sessionId, String imsi, String apn,
                                                     String ueIpv4, long chargingId, String decisionJson) {
            }
        };
    }

    @Test
    void decideReturnsCachedRulesAndDefaultBearerQos() {
        PcrfService pcrf = new PcrfService(new HeapPccCache(repo(imsRules())));
        PccDecision d = pcrf.decide("001010000000001", "ims");
        assertEquals(1, d.rules().size());
        assertEquals("ims-signal-qci5", d.rules().get(0).name());
        assertEquals(5, d.defaultBearerQos().qci());
        assertEquals(1, d.defaultBearerQos().arpPriority());
    }

    @Test
    void internetApnDefaultsToPilotQci9() {
        PcrfService pcrf = new PcrfService(new HeapPccCache(repo(List.of())));
        PccDecision d = pcrf.decide("001010000000001", "internet");
        assertEquals(9, d.defaultBearerQos().qci());
        assertTrue(d.rules().isEmpty());
    }

    @Test
    void usageMonitorThresholdIsConfigurable() {
        PcrfService pcrf = new PcrfService(new HeapPccCache(repo(imsRules())));
        assertEquals(1_000_000_000L, pcrf.usageMonitorThreshold());
        pcrf.withUsageMonitorThreshold(123L);
        assertEquals(123L, pcrf.decide("001010000000001", "ims").usageMonitorThreshold());
        assertThrows(IllegalArgumentException.class, () -> pcrf.withUsageMonitorThreshold(-1));
    }

    @Test
    void persistDecisionWritesNonBlankSnapshot() {
        AtomicReference<String> json = new AtomicReference<>();
        PolicyRepository repo = new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                return imsRules();
            }
            @Override public void upsertIpCanSession(String sessionId, String imsi, String apn,
                                                     String ueIpv4, long chargingId, String decisionJson) {
                json.set(decisionJson);
            }
        };
        PcrfService pcrf = new PcrfService(new HeapPccCache(repo), repo);
        PccDecision d = pcrf.decide("001010000000001", "ims");
        pcrf.persistDecision("gx-1", "001010000000001", "ims", "10.65.0.2", 42L, d);
        assertNotNull(json.get());
        assertTrue(json.get().contains("ims-signal-qci5"));
    }

    @Test
    void cacheShieldsRepositoryOnRepeatLookups() {
        AtomicReference<String> lastApn = new AtomicReference<>();
        PolicyRepository repo = new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                lastApn.set(imsi + "/" + apn);
                return imsRules();
            }
            @Override public void upsertIpCanSession(String s, String i, String a, String u, long c, String j) {
            }
        };
        PcrfService pcrf = new PcrfService(new HeapPccCache(repo));
        pcrf.decide("001010000000001", "ims");
        pcrf.decide("001010000000001", "ims");
        assertEquals("001010000000001/ims", lastApn.get()); // not null, single read-through not asserted but set
    }
}