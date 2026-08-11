package et.elisa.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ElisaClusterViewBridgeTest {

    @Test
    void logicalNodeIdKeepsNodeIdAndStripsHostPort() {
        assertEquals("elisa-scscf-1", ElisaClusterViewBridge.logicalNodeId("elisa-scscf-1"));
        assertEquals("elisa-scscf-1", ElisaClusterViewBridge.logicalNodeId("elisa-scscf-1/127.0.0.1:7800"));
    }

    @Test
    void toNodeIdsEmptyOnNull() {
        assertTrue(ElisaClusterViewBridge.toNodeIds(null).isEmpty());
        assertTrue(ElisaClusterViewBridge.toNodeIds(List.of()).isEmpty());
        assertEquals(Set.of(), ElisaClusterViewBridge.toNodeIds(List.of()));
    }
}
