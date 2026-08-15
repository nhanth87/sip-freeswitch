package et.elisa.esl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import et.elisa.cluster.FsPairRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * R9: remap ESL to checkpoint home FS; home down stays eslLive=false.
 */
class HomeEslRemapServiceTest {

    private static final FsPairRegistry.EslTarget FS1 =
            new FsPairRegistry.EslTarget("fs-1", "127.0.0.1", 8021, "ClueCon", 0);
    private static final FsPairRegistry.EslTarget FS2 =
            new FsPairRegistry.EslTarget("fs-2", "10.0.0.22", 8021, "ClueCon", 0);

    private FsPairRegistry pairs;
    private FreeSwitchRaBridge bridge;
    private HomeEslRemapService remap;

    @BeforeEach
    void setUp() {
        pairs = new FsPairRegistry().memoryOnly("elisa-1", List.of(FS1), FS2);
        bridge = new FreeSwitchRaBridge();
        bridge.setActiveFsNodeId("fs-1");
        remap = new HomeEslRemapService(pairs, bridge);
    }

    @Test
    void remapsToHomeFsWhenPrimaryIsDifferent() {
        HomeEslRemapService.Outcome out = remap.remapToHomeFs("elisa-2", "fs-2");
        assertEquals(HomeEslRemapService.Outcome.HOME_DOWN, out);
        FsPairRegistry.EslTarget rebound = bridge.lastRebind();
        assertEquals("fs-2", rebound.fsNodeId());
        assertEquals("10.0.0.22", rebound.host());
        assertEquals(8021, rebound.port());
        assertEquals("fs-2", bridge.activeFsNodeId());
        assertEquals("fs-2", remap.pinnedHomeFsNodeId());
        assertFalse(bridge.live(), "no adaptor → must not fake eslLive");
    }

    @Test
    void homeFsDownDoesNotFakeLive() {
        assertFalse(bridge.live());
        HomeEslRemapService.Outcome out = remap.remapToHomeFs("fs-2");
        assertEquals(HomeEslRemapService.Outcome.HOME_DOWN, out);
        assertFalse(bridge.live());
        assertFalse(bridge.probeLive());
        assertEquals("fs-2", bridge.lastRebind().fsNodeId());
    }

    @Test
    void skipsRebindWhenPrimaryIsAlreadyHome() {
        HomeEslRemapService.Outcome out = remap.remapToHomeFs("elisa-1", "fs-1");
        assertEquals(HomeEslRemapService.Outcome.HOME_DOWN, out);
        assertNull(bridge.lastRebind());
        assertNull(remap.pinnedHomeFsNodeId());
        assertEquals("fs-1", bridge.activeFsNodeId());
        assertFalse(bridge.live(), "primary=home still must not fake eslLive");
    }

    @Test
    void unknownFsDoesNotRebind() {
        HomeEslRemapService.Outcome out = remap.remapToHomeFs("fs-missing");
        assertEquals(HomeEslRemapService.Outcome.SKIPPED_UNKNOWN_FS, out);
        assertNull(bridge.lastRebind());
        assertEquals("fs-1", bridge.activeFsNodeId());
    }

    @Test
    void blankHomeSkips() {
        assertEquals(HomeEslRemapService.Outcome.SKIPPED_NO_HOME, remap.remapToHomeFs(null));
        assertEquals(HomeEslRemapService.Outcome.SKIPPED_NO_HOME, remap.remapToHomeFs("  "));
        assertNull(bridge.lastRebind());
    }

    @Test
    void alreadyOnHomePinsAndReportsDownWithoutSecondRebind() {
        remap.remapToHomeFs("fs-2");
        FsPairRegistry.EslTarget first = bridge.lastRebind();
        HomeEslRemapService.Outcome again = remap.remapToHomeFs("fs-2");
        assertEquals(HomeEslRemapService.Outcome.HOME_DOWN, again);
        assertSame(first, bridge.lastRebind());
        assertEquals("fs-2", remap.pinnedHomeFsNodeId());
        assertFalse(bridge.live());
    }
}
