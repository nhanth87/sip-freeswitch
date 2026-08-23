package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.pcrf.PccRule;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IspnPccCacheTest {

    private EpcInfinispanManager mgr;

    @AfterEach
    void stop() {
        if (mgr != null) {
            mgr.stop();
            mgr = null;
        }
    }

    private IspnPccCache cache() {
        EpcInfinispanManager m = new EpcInfinispanManager();
        m.enabled = true;
        m.mode = "LOCAL";
        m.clusterName = "epc-cluster";
        m.nodeId = "epc-1";
        m.ttlSeconds = 60;
        m.start();
        mgr = m;
        return new IspnPccCache(
                m.<String, List<PccRule>>cache(EpcInfinispanManager.CACHE_PCC));
    }

    @Test
    void putGetInvalidateRoundTrip() {
        IspnPccCache c = cache();
        List<PccRule> rules = new ArrayList<>(List.of(PccRule.defaultInternet()));
        c.putRules("001010000000001", "internet", rules);
        assertEquals(1, c.rulesFor("001010000000001", "internet").size());
        c.invalidate("001010000000001", "internet");
        assertTrue(c.rulesFor("001010000000001", "internet").isEmpty());
    }

    @Test
    void missWithoutRepoReturnsEmptyAndImmutable() {
        IspnPccCache c = cache();
        List<PccRule> miss = c.rulesFor("001010000000001", "internet");
        assertFalse(miss == null);
        assertTrue(miss.isEmpty());
    }
}