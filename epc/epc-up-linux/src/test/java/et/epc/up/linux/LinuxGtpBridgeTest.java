package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.pfcp.PfcpFteid;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxGtpBridgeTest {

    @Test
    void bridgeEmitsTwoGtpDevices() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        List<LinuxGtpCommand> cmds = agent.sgwBridge(LinuxSgwBridge.standard());

        LinuxGtpCommand access = first(cmds, "ensure-gtp-access");
        LinuxGtpCommand core = first(cmds, "ensure-gtp-core");
        assertTrue(access.argv().contains("gtp-access"));
        assertTrue(core.argv().contains("gtp-core"));
        assertTrue(access.argv().contains("gtp"));
        assertTrue(core.argv().contains("gtp"));
    }

    @Test
    void bridgeUsesFwmarkPolicyRoutingNotSharedTable() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        List<LinuxGtpCommand> cmds = agent.sgwBridge(LinuxSgwBridge.standard());

        LinuxGtpCommand ruleAccess = first(cmds, "ip-rule-access");
        LinuxGtpCommand ruleCore = first(cmds, "ip-rule-core");
        assertTrue(ruleAccess.argv().contains("fwmark"));
        assertTrue(ruleAccess.argv().contains("0x1"));
        assertTrue(ruleAccess.argv().contains("lookup"));
        assertTrue(ruleAccess.argv().contains("100"));
        assertTrue(ruleCore.argv().contains("0x2"));
        assertTrue(ruleCore.argv().contains("200"));

        LinuxGtpCommand routeAccess = first(cmds, "route-access");
        LinuxGtpCommand routeCore = first(cmds, "route-core");
        assertEquals("100", routeAccess.argv().get(routeAccess.argv().indexOf("table") + 1));
        assertEquals("200", routeCore.argv().get(routeCore.argv().indexOf("table") + 1));
        assertNotEquals(routeAccess.argv().get(routeAccess.argv().indexOf("table") + 1),
                routeCore.argv().get(routeCore.argv().indexOf("table") + 1));
    }

    @Test
    void bridgeMarksIngressInterfacesInMangle() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        List<LinuxGtpCommand> cmds = agent.sgwBridge(LinuxSgwBridge.standard());

        LinuxGtpCommand markAccess = first(cmds, "mark-access");
        LinuxGtpCommand markCore = first(cmds, "mark-core");
        assertTrue(markAccess.argv().containsAll(List.of("-t", "mangle", "-j", "MARK", "--set-mark")));
        assertTrue(markAccess.argv().contains("0x1"));
        assertTrue(markCore.argv().contains("0x2"));
    }

    @Test
    void bridgeFromFteidsDerivesHostRoutes() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        PfcpFteid accessPeer = new PfcpFteid(1, InetAddress.getByName("192.0.2.2"), 0);
        PfcpFteid corePeer = new PfcpFteid(2, InetAddress.getByName("198.51.100.9"), 0);
        List<LinuxGtpCommand> cmds = agent.sgwBridge(LinuxSgwBridge.from(accessPeer, corePeer));

        LinuxGtpCommand routeAccess = first(cmds, "route-access");
        LinuxGtpCommand routeCore = first(cmds, "route-core");
        assertTrue(routeAccess.argv().contains("192.0.2.2/32"));
        assertTrue(routeCore.argv().contains("198.51.100.9/32"));
    }

    @Test
    void bridgeRejectsEqualMarks() {
        assertThrows(IllegalArgumentException.class, () ->
                new LinuxSgwBridge("a", "b", 5, 5, "100", "200", "192.0.2.0/24", "198.51.100.0/24"));
    }

    private static LinuxGtpCommand first(List<LinuxGtpCommand> cmds, String description) {
        return cmds.stream().filter(c -> c.description().equals(description)).findFirst().orElseThrow();
    }
}