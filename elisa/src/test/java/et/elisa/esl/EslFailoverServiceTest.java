package et.elisa.esl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import et.elisa.cluster.FsPairRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Pinned home FS must not be replaced by this node's pair walk. */
class EslFailoverServiceTest {

    @Test
    void pinnedHomeRetryDoesNotStealThisNodeFs() {
        FsPairRegistry.EslTarget fs1 =
                new FsPairRegistry.EslTarget("fs-1", "127.0.0.1", 8021, "ClueCon", 0);
        FsPairRegistry.EslTarget fs2 =
                new FsPairRegistry.EslTarget("fs-2", "10.0.0.22", 8021, "ClueCon", 0);
        FsPairRegistry pairs = new FsPairRegistry().memoryOnly("elisa-1", List.of(fs1), fs2);
        FreeSwitchRaBridge bridge = new FreeSwitchRaBridge();
        bridge.setActiveFsNodeId("fs-1");
        HomeEslRemapService remap = new HomeEslRemapService(pairs, bridge);
        remap.remapToHomeFs("elisa-2", "fs-2");
        assertEquals("fs-2", remap.pinnedHomeFsNodeId());
        assertFalse(bridge.live());

        EslFailoverService svc = new EslFailoverService(pairs, bridge, remap);
        svc.probe();

        assertEquals("fs-2", bridge.lastRebind().fsNodeId());
        assertEquals("10.0.0.22", bridge.lastRebind().host());
        assertNotEquals("fs-1", bridge.lastRebind().fsNodeId());
        assertFalse(bridge.live());
    }
}
