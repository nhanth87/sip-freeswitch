package et.epc.up.linux.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import et.epc.api.UeAddresses;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.LinuxGtpCommand;
import et.epc.up.linux.events.UpfProgramSessionEvent;
import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UpfSbb procedures driven directly, without a live SLEE container or PFCP wire. */
class UpfSbbTest {

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort pfcpPort = sent::add;

    private UpfSbb.$Concrete sbb;

    @BeforeEach
    void setUp() {
        sbb = new UpfSbb.$Concrete(new LinuxGtpAgent("gtp0"));
        inject(sbb, "pfcpRa", pfcpPort);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = UpfSbb.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reuses the LinuxGtpAgentTest fixture shape. */
    private static PfcpRule rule() throws Exception {
        PfcpFteid local = new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0);
        PfcpFteid remote = new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0);
        return new PfcpRule(9L, "pdr", local, remote, "qer-1", true);
    }

    private static UeAddresses ue() throws Exception {
        return new UeAddresses((Inet4Address) InetAddress.getByName("10.64.0.2"), null);
    }

    @Test
    void installEmitsLinuxGtpRecipesAndPfcpProgram() throws Exception {
        PfcpRule rule = rule();
        sbb.onEvent(new UpfProgramSessionEvent(9L, rule, ue()), null);

        assertEquals(9L, sbb.getPfcpSeid());

        List<LinuxGtpCommand> cmds = sbb.lastCommands();
        assertEquals(3, cmds.size());
        assertEquals("ensure-gtp-device", cmds.get(0).description());
        assertEquals("add-tunnel", cmds.get(1).description());
        assertEquals("anti-spoof", cmds.get(2).description());
        assertTrue(cmds.get(1).argv().contains("2"));
        assertTrue(cmds.stream().anyMatch(c -> c.argv().contains("10.64.0.2")));
        assertTrue(cmds.stream().noneMatch(c -> String.join(" ", c.argv()).contains("java")));

        assertEquals(1, sent.size());
        PfcpProgramSession sp = (PfcpProgramSession) sent.get(0);
        assertEquals(9L, sp.seid());
        assertEquals(List.of(rule), sp.rules());
    }

    @Test
    void deleteEmitsDelTunnelRecipeAndEmptyPfcpProgram() throws Exception {
        PfcpRule rule = rule();
        sbb.onEvent(new UpfProgramSessionEvent(9L, rule, null), null);

        assertEquals(9L, sbb.getPfcpSeid());

        List<LinuxGtpCommand> cmds = sbb.lastCommands();
        assertEquals(1, cmds.size());
        assertEquals("del-tunnel", cmds.get(0).description());
        assertTrue(cmds.get(0).argv().contains("1"));

        assertEquals(1, sent.size());
        PfcpProgramSession sp = (PfcpProgramSession) sent.get(0);
        assertEquals(9L, sp.seid());
        assertTrue(sp.rules().isEmpty());
    }

    @Test
    void unknownEventIsIgnored() throws Exception {
        sbb.onEvent(new com.microjainslee.api.SleeEvent() {}, null);
        assertTrue(sbb.lastCommands().isEmpty());
        assertTrue(sent.isEmpty());
    }
}