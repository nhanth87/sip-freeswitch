package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import et.epc.api.UeAddresses;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxGtpAgentTest {

    @Test
    void recipesNeverClaimJavaForwarding() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        PfcpFteid local = new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0);
        PfcpFteid remote = new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0);
        UeAddresses ue = new UeAddresses(
                (Inet4Address) InetAddress.getByName("10.64.0.2"), null);
        List<LinuxGtpCommand> cmds = agent.install(new PfcpRule(9, "pdr", local, remote, "q", true), ue);
        assertFalse(cmds.isEmpty());
        assertTrue(cmds.stream().anyMatch(c -> c.argv().contains("type") && c.argv().contains("gtp")));
        assertTrue(cmds.stream().noneMatch(c -> String.join(" ", c.argv()).contains("java")));
    }
}
