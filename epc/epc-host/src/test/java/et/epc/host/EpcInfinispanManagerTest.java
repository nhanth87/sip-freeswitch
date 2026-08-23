package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EpcInfinispanManagerTest {

    private EpcInfinispanManager mgr;

    @AfterEach
    void stop() {
        if (mgr != null) {
            mgr.stop();
            mgr = null;
        }
    }

    private static EpcInfinispanManager localMgr() {
        EpcInfinispanManager m = new EpcInfinispanManager();
        m.enabled = true;
        m.mode = "LOCAL";
        m.clusterName = "epc-cluster";
        m.nodeId = "epc-1";
        m.ttlSeconds = 60;
        return m;
    }

    @Test
    void rejectsElisaClusterName() {
        EpcInfinispanManager m = localMgr();
        m.clusterName = "elisa-cluster";
        assertThrows(IllegalArgumentException.class, m::start);
        assertNull(m.manager());
    }

    @Test
    void createsSessionsAndSubscribersCachesLocal() {
        mgr = localMgr();
        mgr.start();
        assertTrue(mgr.enabled());
        assertNotNull(mgr.manager());
        assertFalse(mgr.distSync());
        assertEquals("LOCAL", mgr.modeLabel());
        assertEquals("epc-cluster", mgr.clusterName());
        assertEquals("epc-1", mgr.nodeId());
        assertNotNull(mgr.cache(EpcInfinispanManager.CACHE_SESSIONS));
        assertNotNull(mgr.cache(EpcInfinispanManager.CACHE_SUBSCRIBERS));
        assertNotNull(mgr.cache(EpcInfinispanManager.CACHE_PCC));
    }
}