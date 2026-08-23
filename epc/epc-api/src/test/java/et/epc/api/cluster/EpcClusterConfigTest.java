package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EpcClusterConfigTest {

    @Test
    void localDefaultsToEpcClusterNodeIndex1() {
        EpcClusterConfig c = EpcClusterConfig.local("epc-sgwc-1");
        assertEquals("epc-sgwc-1", c.nodeId());
        assertEquals(ClusterMode.LOCAL, c.mode());
        assertEquals("epc-cluster", c.clusterName());
        assertEquals(1, c.nodeIndex());
    }

    @Test
    void rejectsElisaClusterName() {
        assertThrows(IllegalArgumentException.class,
                () -> new EpcClusterConfig("n", ClusterMode.DIST_SYNC, "elisa-cluster", 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 256, 1000})
    void rejectsOutOfRangeNodeIndex(int idx) {
        assertThrows(IllegalArgumentException.class,
                () -> new EpcClusterConfig("n", ClusterMode.LOCAL, "epc-cluster", idx));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new EpcClusterConfig(null, ClusterMode.LOCAL, "epc-cluster", 1));
        assertThrows(NullPointerException.class,
                () -> new EpcClusterConfig("n", null, "epc-cluster", 1));
        assertThrows(NullPointerException.class,
                () -> new EpcClusterConfig("n", ClusterMode.LOCAL, null, 1));
    }
}