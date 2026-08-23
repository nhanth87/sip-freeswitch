package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import et.epc.api.UeAddresses;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxGtpAgentMoreTest {

    private static PfcpRule rule() throws Exception {
        return new PfcpRule(9, "pdr", new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0),
                new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0), "q", true);
    }

    @Test
    void installAddsAntiSpoofOnlyWithIpv4Ue() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        List<LinuxGtpCommand> cmds = agent.install(rule(), null);
        assertTrue(cmds.stream().noneMatch(c -> c.argv().contains("iptables")));

        UeAddresses ue = new UeAddresses(
                (java.net.Inet4Address) InetAddress.getByName("10.64.0.2"), null);
        cmds = agent.install(rule(), ue);
        assertTrue(cmds.stream().anyMatch(c -> c.argv().contains("iptables")));
        assertTrue(cmds.stream().anyMatch(c -> c.argv().contains("10.64.0.2")));
    }

    @Test
    void installEmitsEnsureAndTunnelRecipes() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp1");
        List<LinuxGtpCommand> cmds = agent.install(rule(), null);
        assertEquals(2, cmds.size());
        assertEquals("ensure-gtp-device", cmds.get(0).description());
        assertEquals("add-tunnel", cmds.get(1).description());
        assertTrue(cmds.get(1).argv().contains("2"));
    }

    @Test
    void deleteEmitsDelTunnel() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        List<LinuxGtpCommand> cmds = agent.delete(rule());
        assertEquals(1, cmds.size());
        assertEquals("del-tunnel", cmds.get(0).description());
        assertTrue(cmds.get(0).argv().contains("1"));
    }

    @Test
    void nullDeviceRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> new LinuxGtpAgent(null));
    }

    @Test
    void nullRuleRejected() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> agent.install(null, null));
    }
}