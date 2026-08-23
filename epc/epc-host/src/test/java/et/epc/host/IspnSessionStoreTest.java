package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.cluster.SessionCheckpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IspnSessionStoreTest {

    private EpcInfinispanManager mgr;

    @AfterEach
    void stop() {
        if (mgr != null) {
            mgr.stop();
            mgr = null;
        }
    }

    private IspnSessionStore store() {
        EpcInfinispanManager m = new EpcInfinispanManager();
        m.enabled = true;
        m.mode = "LOCAL";
        m.clusterName = "epc-cluster";
        m.nodeId = "epc-1";
        m.ttlSeconds = 60;
        m.start();
        mgr = m;
        return new IspnSessionStore(
                m.<String, SessionCheckpoint>cache(EpcInfinispanManager.CACHE_SESSIONS));
    }

    private static SessionCheckpoint cp(String id, String role, String owner, int s11cTeid) {
        return new SessionCheckpoint(id, role, "001010000000001", "internet", 5,
                owner, 1L, "10.64.0.2", null, null, null, 42L, "ff", s11cTeid, 7L);
    }

    @Test
    void putGetRemoveSize() {
        IspnSessionStore s = store();
        s.put(cp("sgw|1", "sgw", "epc-1", 10));
        assertEquals(1, s.size());
        assertTrue(s.get("sgw|1").isPresent());
        s.put(cp("sgw|1", "sgw", "epc-1", 11));
        assertEquals(1, s.size());
        assertEquals(11, s.get("sgw|1").orElseThrow().s11cTeid());
        s.remove("sgw|1");
        assertEquals(0, s.size());
        assertTrue(s.get("sgw|1").isEmpty());
    }

    @Test
    void getUnknownReturnsEmpty() {
        IspnSessionStore s = store();
        assertTrue(s.get("missing").isEmpty());
    }

    @Test
    void findByS11cTeidOnlyMatchesSgwRole() {
        IspnSessionStore s = store();
        s.put(cp("sgw|1", "sgw", "epc-1", 10));
        s.put(cp("pgw|2", "pgw", "epc-1", 20));
        assertTrue(s.findByS11cTeid(10).isPresent());
        assertFalse(s.findByS11cTeid(20).isPresent());
        assertFalse(s.findByS11cTeid(999).isPresent());
    }
}