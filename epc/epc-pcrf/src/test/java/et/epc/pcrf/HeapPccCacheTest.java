package et.epc.pcrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HeapPccCacheTest {

    @Test
    void readThroughBacksOntoRepositoryOncePerKey() {
        AtomicInteger loads = new AtomicInteger();
        PolicyRepository repo = new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                loads.incrementAndGet();
                return List.of(PccRule.defaultInternet());
            }
            @Override public void upsertIpCanSession(String s, String i, String a, String u, long c, String j) {
            }
        };
        HeapPccCache cache = new HeapPccCache(repo);
        assertEquals(1, cache.rulesFor("001010000000001", "internet").size());
        cache.rulesFor("001010000000001", "internet");
        assertEquals(1, loads.get());
    }

    @Test
    void putThenInvalidateRefreshesFromRepository() {
        AtomicInteger loads = new AtomicInteger();
        PolicyRepository repo = new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                loads.incrementAndGet();
                return List.of(PccRule.defaultInternet());
            }
            @Override public void upsertIpCanSession(String s, String i, String a, String u, long c, String j) {
            }
        };
        HeapPccCache cache = new HeapPccCache(repo);
        cache.putRules("001010000000001", "internet", List.of(PccRule.imsSignalQci5()));
        assertEquals("ims-signal-qci5", cache.rulesFor("001010000000001", "internet").get(0).name());
        cache.invalidate("001010000000001", "internet");
        assertEquals("internet-default", cache.rulesFor("001010000000001", "internet").get(0).name());
    }

    @Test
    void noRepositoryMissIsEmpty() {
        assertTrue(new HeapPccCache().rulesFor("001010000000001", "internet").isEmpty());
    }
}