package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import et.epc.api.UeAddresses;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxGtpQerTest {

    private static PfcpRule rule(String qerId) throws Exception {
        PfcpFteid local = new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0);
        PfcpFteid remote = new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0);
        return new PfcpRule(9L, "pdr", local, remote, qerId, true);
    }

    private static UeAddresses ue() throws Exception {
        return new UeAddresses((Inet4Address) InetAddress.getByName("10.64.0.2"), null);
    }

    @Test
    void qerEmitsHtbQdiscClassAndFilters() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        LinuxQer qer = LinuxQer.of("qer-1", 100_000_000L, 200_000_000L, 20_000_000L, 50_000_000L);
        List<LinuxGtpCommand> cmds = agent.qer(rule("qer-1"), ue(), qer);

        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-qdisc")));
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-class-ul")));
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-class-dl")));
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-filter-ul")));
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-filter-dl")));

        LinuxGtpCommand ulFilter = first(cmds, "qer-filter-ul");
        assertTrue(ulFilter.argv().contains("src"));
        assertTrue(ulFilter.argv().contains("10.64.0.2"));
        assertTrue(ulFilter.argv().contains("flowid"));
        assertTrue(ulFilter.argv().contains("1:10"));

        LinuxGtpCommand dlFilter = first(cmds, "qer-filter-dl");
        assertTrue(dlFilter.argv().contains("dst"));
        assertTrue(dlFilter.argv().contains("1:11"));

        LinuxGtpCommand ulClass = first(cmds, "qer-class-ul");
        assertTrue(ulClass.argv().contains("20000000bit"));
        assertTrue(ulClass.argv().contains("100000000bit"));
        assertTrue(ulClass.argv().contains("htb"));
    }

    @Test
    void qerRequiresMatchingQerId() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        LinuxQer qer = LinuxQer.of("qer-2", 1, 1, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> agent.qer(rule("qer-1"), ue(), qer));
    }

    @Test
    void qerSkipsWithoutUeIpv4() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        LinuxQer qer = LinuxQer.of("qer-1", 1, 1, 0, 0);
        assertTrue(agent.qer(rule("qer-1"), null, qer).isEmpty());
    }

    @Test
    void qerDeleteEmitsFilterAndClassTeardown() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        List<LinuxGtpCommand> cmds = agent.qerDelete(rule("qer-1"), ue());
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-del-filter")));
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-del-class-ul")));
        assertTrue(cmds.stream().anyMatch(c -> c.description().equals("qer-del-class-dl")));
        assertTrue(cmds.stream().anyMatch(c -> c.argv().contains("class") && c.argv().contains("del")));
    }

    @Test
    void qerRejectsGbrAboveMbr() {
        assertThrows(IllegalArgumentException.class,
                () -> LinuxQer.of("qer-1", 1000, 1000, 2000, 0));
    }

    private static LinuxGtpCommand first(List<LinuxGtpCommand> cmds, String description) {
        return cmds.stream().filter(c -> c.description().equals(description)).findFirst().orElseThrow();
    }
}