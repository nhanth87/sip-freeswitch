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
import com.microjainslee.ra.pfcp.PfcpIes;
import com.microjainslee.ra.pfcp.command.PfcpSessionAnswer;
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
        PfcpSessionAnswer resp = (PfcpSessionAnswer) sent.get(0);
        assertEquals(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, resp.request().type());
        assertEquals(PfcpIe.CAUSE_REQUEST_ACCEPTED, resp.cause());
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
        PfcpSessionAnswer del = (PfcpSessionAnswer) sent.get(1);
        assertEquals(PfcpMessageType.SESSION_DELETION_REQUEST, del.request().type());
        assertEquals(PfcpIe.CAUSE_REQUEST_ACCEPTED, del.cause());
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
        PfcpSessionAnswer resp = (PfcpSessionAnswer) sent.get(1);
        assertEquals(PfcpMessageType.SESSION_MODIFICATION_REQUEST, resp.request().type());
        assertEquals(2, resp.request().sequence());
        List<PfcpUsageReport> reports = PfcpCodec.decodeUsageReports(PfcpCodec.encodeIes(resp.ies()));
        assertEquals(List.of(new PfcpUsageReport(5, PfcpIe.UT_PERIO, 400L, 0L, 0)), reports);
        assertEquals(400L, agent.readCounter("5").orElseThrow().deltaBytes(),
                "Query-URR is read-only: baseline does not advance");
    }

    /** TS 29.244 §7.5.4: a Modification for a session the UP does not hold → Cause 65, nothing installed. */
    @Test
    void modificationOfAnUnknownSessionIsContextNotFound() throws Exception {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        UpfPfcpSbb.$Concrete local = sbbWith(agent, null);

        byte[] payload = PfcpCodec.encodeIes(PfcpIes.fromRules(
                List.of(ruleWithUrr(new PfcpUrr(5))), InetAddress.getByName("10.64.0.2")));
        local.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_MODIFICATION_REQUEST, 9L, 3, payload), CP), null);

        assertEquals(1, sent.size());
        assertEquals(65, ((PfcpSessionAnswer) sent.get(0)).cause());
        assertEquals(0, agent.urr().size());
        assertTrue(local.lastCommands().isEmpty());
    }

    /**
     * W-4: Create Session buffers the downlink; Modify Bearer = Update FAR → one new tunnel,
     * the uplink untouched; Release Access Bearers = Update FAR BUFF → only that tunnel goes.
     */
    @Test
    void updateFarMovesOnlyTheDownlinkTunnel() throws Exception {
        InetAddress ue = InetAddress.getByName("10.64.0.2");
        byte[] est = PfcpCodec.encodeIes(List.of(
                PfcpIes.createPdr(new com.microjainslee.ra.pfcp.PfcpPdr((short) 1, 255, PfcpIe.INTERFACE_ACCESS,
                        new PfcpFteid(0x1001, InetAddress.getByName("192.0.2.16"), 0), ue, 1, List.of(1), List.of(),
                        false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new com.microjainslee.ra.pfcp.PfcpFar(1, PfcpIe.APPLY_FORW,
                        new PfcpFteid(0x2001, InetAddress.getByName("192.0.2.13"), 0)), PfcpIe.INTERFACE_CORE),
                PfcpIes.createPdr(new com.microjainslee.ra.pfcp.PfcpPdr((short) 2, 254, PfcpIe.INTERFACE_CORE,
                        new PfcpFteid(0x1002, InetAddress.getByName("192.0.2.16"), 0), ue, 2, List.of(1), List.of(),
                        false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new com.microjainslee.ra.pfcp.PfcpFar(2, PfcpIe.APPLY_BUFF | PfcpIe.APPLY_NOCP, null),
                        PfcpIe.INTERFACE_ACCESS)));
        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 0L, 1,
                PfcpCodec.encodeIes(concat(List.of(PfcpIes.fSeid(9L, InetAddress.getByName("192.0.2.14"))),
                        PfcpCodec.decodeIes(est)))), CP), null);
        assertEquals(1, sbb.tunnels(9L).size(), "downlink buffered: uplink tunnel only");

        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(PfcpMessageType.SESSION_MODIFICATION_REQUEST, 9L, 2,
                PfcpCodec.encodeIes(List.of(PfcpIes.updateFar(2, PfcpIe.APPLY_FORW,
                        new PfcpFteid(0xe001, InetAddress.getByName("192.0.2.1"), 0), PfcpIe.INTERFACE_ACCESS)))),
                CP), null);
        assertEquals(2, sbb.tunnels(9L).size());
        assertEquals(List.of("add-tunnel", "anti-spoof"), sbb.lastCommands().stream()
                .map(LinuxGtpCommand::description).filter(d -> !d.startsWith("ensure")).toList(),
                "only the downlink tunnel is added");

        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(PfcpMessageType.SESSION_MODIFICATION_REQUEST, 9L, 3,
                PfcpCodec.encodeIes(List.of(PfcpIes.updateFar(2, PfcpIe.APPLY_BUFF | PfcpIe.APPLY_NOCP, null,
                        PfcpIe.INTERFACE_ACCESS)))), CP), null);
        assertEquals(1, sbb.tunnels(9L).size());
        assertEquals(List.of("del-tunnel"), sbb.lastCommands().stream().map(LinuxGtpCommand::description).toList());
        assertTrue(sent.stream().allMatch(c -> ((PfcpSessionAnswer) c).cause() == PfcpIe.CAUSE_REQUEST_ACCEPTED));

        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(PfcpMessageType.SESSION_MODIFICATION_REQUEST, 9L, 4,
                PfcpCodec.encodeIes(List.of(PfcpIes.updateFar(7, PfcpIe.APPLY_FORW, null, PfcpIe.INTERFACE_ACCESS)))),
                CP), null);
        assertEquals(73, ((PfcpSessionAnswer) sent.get(sent.size() - 1)).cause(), "unknown FAR → rule failure");
    }

    /** Pooled entities: Establishment on one entity, Modification on another — same table. */
    @Test
    void modificationOnAnotherEntitySeesTheSession() throws Exception {
        et.epc.up.linux.UpfSessionTable table = new et.epc.up.linux.UpfSessionTable();
        UpfPfcpSbb.$Concrete a = new UpfPfcpSbb.$Concrete(new LinuxGtpAgent("gtp0"), null, table);
        UpfPfcpSbb.$Concrete b = new UpfPfcpSbb.$Concrete(new LinuxGtpAgent("gtp0"), null, table);
        inject(a, "pfcpRa", pfcpPort);
        inject(b, "pfcpRa", pfcpPort);
        a.onEvent(new PfcpMessageEvent(new PfcpMessage(PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 9L, 1,
                PfcpCodec.encodeRules(List.of(rule()), InetAddress.getByName("10.64.0.2"))), CP), null);
        b.onEvent(new PfcpMessageEvent(new PfcpMessage(PfcpMessageType.SESSION_DELETION_REQUEST, 9L, 2,
                new byte[0]), CP), null);
        assertEquals(PfcpIe.CAUSE_REQUEST_ACCEPTED, ((PfcpSessionAnswer) sent.get(1)).cause());
        assertEquals(0, table.size());
    }

    private static List<PfcpIe> concat(List<PfcpIe> a, List<PfcpIe> b) {
        List<PfcpIe> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
