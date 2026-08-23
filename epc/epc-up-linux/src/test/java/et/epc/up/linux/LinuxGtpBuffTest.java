package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxGtpBuffTest {

    private static PfcpRule dlRule() throws Exception {
        PfcpFteid local = new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0);
        PfcpFteid remote = new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0);
        return new PfcpRule(77L, "pdr-dl", local, remote, "qer-1", false);
    }

    @Test
    void buffQueuesRuleAndEmitsNoKernelRecipe() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        PfcpRule rule = dlRule();

        List<LinuxGtpCommand> cmds = agent.bufferApplyAction(rule);
        assertTrue(cmds.isEmpty());

        assertEquals(1, agent.downlinkBuffer().pendingCount(77L));
        assertEquals(1, agent.downlinkBuffer().sessions());
        assertFalse(agent.downlinkBuffer().isEmpty());

        List<PfcpRule> drained = agent.downlinkBuffer().drain(77L);
        assertEquals(List.of(rule), drained);
        assertTrue(agent.downlinkBuffer().isEmpty());
    }

    @Test
    void buffRejectsNullRule() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        assertThrows(NullPointerException.class, () -> agent.bufferApplyAction(null));
    }
}