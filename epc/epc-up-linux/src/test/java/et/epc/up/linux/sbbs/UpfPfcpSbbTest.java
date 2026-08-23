package et.epc.up.linux.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.pfcp.PfcpCodec;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpMessageType;
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.PfcpUrr;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.LinuxGtpCommand;
import et.epc.up.linux.LinuxUrrSpec;
import et.epc.up.linux.UrrIpIndex;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Inbound PFCP session rules decode into Linux GTP recipes and answer a response. */
class UpfPfcpSbbTest {

    private static final InetSocketAddress CP = new InetSocketAddress("192.0.2.20", 8805);

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort pfcpPort = sent::add;

    private UpfPfcpSbb.$Concrete sbb;

    @BeforeEach
    void setUp() {
        sbb = new UpfPfcpSbb.$Concrete(new LinuxGtpAgent("gtp0"));
        inject(sbb, "pfcpRa", pfcpPort);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = UpfPfcpSbb.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PfcpRule rule() throws Exception {
        return new PfcpRule(9L, "pdr",
                new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0),
                new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0),
                "qer-1", true);
    }

    @Test
    void establishmentRequestEmitsRecipesAndResponse() throws Exception {
        PfcpRule r = rule();
        byte[] payload = PfcpCodec.encodeRules(List.of(r), InetAddress.getByName("10.64.0.2"));
        PfcpMessage req = new PfcpMessage(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 5, payload);
        sbb.onEvent(new PfcpMessageEvent(req, CP), null);

        assertEquals(9L, sbb.getPfcpSeid());
        List<LinuxGtpCommand> cmds = sbb.lastCommands();
        assertEquals(3, cmds.size(), "baseline install = device + tunnel + anti-spoof");
        assertEquals("ensure-gtp-device", cmds.get(0).description());
        assertTrue(cmds.stream().anyMatch(c -> c.argv().contains("10.64.0.2")));

        assertEquals(1, sent.size());
        SendPfcpMessage resp = (SendPfcpMessage) sent.get(0);
        assertEquals(PfcpMessageType.SESSION_ESTABLISHMENT_RESPONSE, resp.message().type());
        assertEquals(9L, resp.message().seid());
    }

    @Test
    void deletionRequestEmitsDeleteRecipes() throws Exception {
        PfcpRule r = rule();
        byte[] payload = PfcpCodec.encodeRules(List.of(r), null);
        sbb.onEvent(new PfcpMessageEvent(
                new PfcpMessage(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 1, payload), CP), null);

        sbb.onEvent(new PfcpMessageEvent(
                new PfcpMessage(PfcpMessageType.SESSION_DELETION_REQUEST, 9L, 2, new byte[0]), CP), null);

        List<LinuxGtpCommand> cmds = sbb.lastCommands();
        assertEquals(1, cmds.size());
        assertEquals("del-tunnel", cmds.get(0).description());

        assertEquals(2, sent.size());
        SendPfcpMessage del = (SendPfcpMessage) sent.get(1);
        assertEquals(PfcpMessageType.SESSION_DELETION_RESPONSE, del.message().type());
    }

    @Test
    void unknownEventIsIgnored() throws Exception {
        sbb.onEvent(new com.microjainslee.api.SleeEvent() {}, null);
        assertTrue(sbb.lastCommands().isEmpty());
        assertTrue(sent.isEmpty());
    }

    private static PfcpRule ruleWithUrr(PfcpUrr urr) throws Exception {
        return new PfcpRule(9L, "pdr",
                new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 0),
                new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 0),
                "qer-1", true, urr);
    }

    private UpfPfcpSbb.$Concrete sbbWith(LinuxGtpAgent agent, UrrIpIndex ipIndex) {
        UpfPfcpSbb.$Concrete local = new UpfPfcpSbb.$Concrete(agent, ipIndex);
        inject(local, "pfcpRa", pfcpPort);
        return local;
    }

    @Test
    void establishmentRegistersUrrCounterAndIpIndex() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        UrrIpIndex ipIndex = new UrrIpIndex();
        UpfPfcpSbb.$Concrete local = sbbWith(agent, ipIndex);

        PfcpUrr urr = new PfcpUrr(5, PfcpIe.MM_VOLUME, PfcpIe.UT_PERIO | PfcpIe.UT_VOLTH, 1000L, 60);
        byte[] payload = PfcpCodec.encodeRules(List.of(ruleWithUrr(urr)),
                InetAddress.getByName("10.64.0.2"));
        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 1, payload), CP), null);

        assertEquals(1, agent.urr().size());
        LinuxUrrSpec spec = agent.urr().spec("5").orElseThrow();
        assertEquals(9L, spec.seid());
        assertEquals(1000L, spec.volumeThresholdBytes());
        assertEquals(60, spec.timeThresholdSeconds());
        assertTrue(spec.uplink());
        assertEquals("5", ipIndex.urrForIp("10.64.0.2"));
        assertEquals(1, sent.size());
    }

    @Test
    void deletionRemovesUrrCounterAndIpIndex() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        UrrIpIndex ipIndex = new UrrIpIndex();
        UpfPfcpSbb.$Concrete local = sbbWith(agent, ipIndex);

        byte[] payload = PfcpCodec.encodeRules(
                List.of(ruleWithUrr(new PfcpUrr(5))), InetAddress.getByName("10.64.0.2"));
        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 1, payload), CP), null);
        assertEquals(1, agent.urr().size());

        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_DELETION_REQUEST, 9L, 2, new byte[0]), CP), null);

        assertEquals(0, agent.urr().size());
        assertEquals(0, ipIndex.size());
    }

    @Test
    void queryUrrRespondsWithUsageSnapshot() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        UpfPfcpSbb.$Concrete local = sbbWith(agent, null);

        byte[] establish = PfcpCodec.encodeRules(
                List.of(ruleWithUrr(new PfcpUrr(5, PfcpIe.MM_VOLUME, PfcpIe.UT_PERIO, 0L, 0))),
                InetAddress.getByName("10.64.0.2"));
        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 1, establish), CP), null);
        agent.urr().addBytes("5", 400);

        byte[] query = PfcpCodec.encodeIes(List.of(
                new PfcpIe(PfcpIe.QUERY_URR, new byte[] {0, 0, 0, 5})));
        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_MODIFICATION_REQUEST, 9L, 2, query), CP), null);

        assertEquals(2, sent.size());
        SendPfcpMessage resp = (SendPfcpMessage) sent.get(1);
        assertEquals(PfcpMessageType.SESSION_MODIFICATION_RESPONSE, resp.message().type());
        assertEquals(9L, resp.message().seid());
        assertEquals(2, resp.message().sequence());
        List<PfcpUsageReport> reports = PfcpCodec.decodeUsageReports(resp.message().payload());
        assertEquals(List.of(new PfcpUsageReport(5, PfcpIe.UT_PERIO, 400L, 0L, 0)), reports);
        assertEquals(400L, agent.readCounter("5").orElseThrow().deltaBytes(),
                "Query-URR is read-only: baseline does not advance");
    }

    @Test
    void modificationWithoutQueryUrrStillInstalls() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        UpfPfcpSbb.$Concrete local = sbbWith(agent, null);

        byte[] payload = PfcpCodec.encodeRules(
                List.of(ruleWithUrr(new PfcpUrr(5))), InetAddress.getByName("10.64.0.2"));
        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_MODIFICATION_REQUEST, 9L, 3, payload), CP), null);

        assertEquals(1, sent.size());
        SendPfcpMessage resp = (SendPfcpMessage) sent.get(0);
        assertEquals(PfcpMessageType.SESSION_MODIFICATION_RESPONSE, resp.message().type());
        assertEquals(0, resp.message().payload().length);
        assertEquals(1, agent.urr().size());
    }
}