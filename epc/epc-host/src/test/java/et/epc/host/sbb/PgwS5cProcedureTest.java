package et.epc.host.sbb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterTimeoutEvent;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.pfcp.command.PfcpSessionEstablishment;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.pcef.PcefService;
import et.epc.pgwc.GxWireConfig;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import et.epc.pgwc.WirePcrfClient;
import et.epc.sgwc.GtpcShapes;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * W-1 seen on the wire: the PGW-C waited 2 s on the CCA on its own SBB thread, the CCA (405 ms)
 * could not be delivered, and the PCRF's answer was ignored. Now: CCR-I and return; the S5-C
 * response is sent on the CCA (or timeout) event with the PCRF's QoS.
 */
class PgwS5cProcedureTest {

    private static final Imsi IMSI = new Imsi("001010000000009");
    private static final InetSocketAddress SGWC = new InetSocketAddress("192.0.2.14", 2123);

    private static final class Port implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            cmds.add(command);
        }

        <T> List<T> of(Class<T> type) {
            return cmds.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private final Port gtp = new Port();
    private final Port pfcp = new Port();
    private final Port diameter = new Port();
    private Ipam ipam;
    private PgwS5cProcedure proc;
    private Fteid sgwS5c;
    private Fteid sgwS5u;

    @BeforeEach
    void setUp() throws Exception {
        ipam = new Ipam();
        PgwControl pgw = new PgwControl(EpcClusterConfig.local("epc-pgwc-1"), ipam, new PcefService(),
                new HeapSessionStore(), pfcp, InetAddress.getByName("192.0.2.13"),
                new InetSocketAddress("192.0.2.16", 8805));
        WirePcrfClient gx = new WirePcrfClient(diameter, () -> true,
                new GxWireConfig(true, "pcrf.epc.lab", 3868, 2_000L, "epc.lab"), "pgwc.epc.lab");
        proc = new PgwS5cProcedure(pgw, gx, gtp);
        sgwS5c = new Fteid(0x0101_0010, InetAddress.getByName("192.0.2.14"), GtpcShapes.IF_S5C_SGW);
        sgwS5u = new Fteid(0x0101_0011, InetAddress.getByName("192.0.2.14"), GtpcShapes.IF_S5U_SGW);
    }

    private Gtpv2Message csr() {
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 7, (byte) 1, List.of(
                new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd(IMSI.digits()))));
    }

    private static DiameterAnswerEvent cca(String sid, long rc, List<DiameterAvp> avps) {
        return new DiameterAnswerEvent(sid, 16_777_238L, 272, rc, 1L, 1L, "pcrf.epc.lab", "epc.lab",
                Map.of(), avps);
    }

    @Test
    void ccaCompletesTheSessionWithThePcrfQos() {
        proc.begin(csr(), SGWC, IMSI, ApnConfig.internet(), sgwS5c, sgwS5u);
        assertEquals(1, diameter.of(SendDiameterRequest.class).size(), "CCR-I sent");
        assertTrue(gtp.cmds.isEmpty(), "no S5-C answer before the CCA");
        assertTrue(pfcp.of(PfcpSessionEstablishment.class).isEmpty(), "no Sxb before the policy");
        assertEquals(1, proc.pendingCount());

        String sid = diameter.of(SendDiameterRequest.class).get(0).sessionId();
        DiameterAvp dq = DiameterAvp.grouped3gpp(1049, List.of(DiameterAvp.v3gpp(1028, 6L),
                DiameterAvp.grouped3gpp(1034, List.of(DiameterAvp.u32(1046, 10415L, 3L, true),
                        DiameterAvp.v3gpp(1047, 1L), DiameterAvp.v3gpp(1048, 0L)))));
        DiameterAvp ambr = DiameterAvp.grouped3gpp(1016, List.of(
                DiameterAvp.u32(1041, 10415L, 20_000_000L, true), DiameterAvp.u32(1040, 10415L, 40_000_000L, true)));
        assertTrue(proc.onCca(cca(sid, 2001L, List.of(dq, ambr))));

        assertEquals(0, proc.pendingCount());
        assertEquals(1, pfcp.of(PfcpSessionEstablishment.class).size(), "Sxb after the CCA");
        Gtpv2Message rsp = gtp.of(SendGtpv2Message.class).get(0).message();
        assertEquals(Gtpv2MessageType.CREATE_SESSION_RESPONSE, rsp.type());
        assertEquals(sgwS5c.teid(), rsp.teid(), "§5.5.2 header TEID = SGW S5-C TEID");
        assertEquals(16, GtpcShapes.cause(rsp));
        byte[] qos = GtpcShapes.bearerIe(GtpcShapes.firstBearer(rsp), GtpcShapes.BEARER_QOS);
        assertNotNull(qos);
        assertEquals(6, qos[1], "QCI from Default-EPS-Bearer-QoS");
        assertEquals(3, (qos[0] >> 2) & 0x0f, "ARP priority");
        Gtpv2Ie apnAmbr = rsp.first(GtpcShapes.AMBR);
        assertNotNull(apnAmbr);
        assertEquals(20_000, java.nio.ByteBuffer.wrap(apnAmbr.value()).getInt(), "APN-AMBR UL kbps");
        assertEquals(40_000, java.nio.ByteBuffer.wrap(apnAmbr.value()).getInt(4), "APN-AMBR DL kbps");
    }

    @Test
    void txTimeoutCompletesWithTheLocalPolicy() {
        proc.begin(csr(), SGWC, IMSI, ApnConfig.internet(), sgwS5c, sgwS5u);
        String sid = diameter.of(SendDiameterRequest.class).get(0).sessionId();
        assertTrue(proc.onTimeout(new DiameterTimeoutEvent(sid, 16_777_238L, 272)));
        Gtpv2Message rsp = gtp.of(SendGtpv2Message.class).get(0).message();
        assertEquals(16, GtpcShapes.cause(rsp));
        assertEquals(9, GtpcShapes.bearerIe(GtpcShapes.firstBearer(rsp), GtpcShapes.BEARER_QOS)[1],
                "static default QCI 9");
        assertEquals(0, proc.pendingCount());
    }

    @Test
    void pcrfRefusalRejectsAndReleasesTheAddress() {
        proc.begin(csr(), SGWC, IMSI, ApnConfig.internet(), sgwS5c, sgwS5u);
        assertEquals(1, ipam.size());
        String sid = diameter.of(SendDiameterRequest.class).get(0).sessionId();
        assertTrue(proc.onCca(cca(sid, 5030L, List.of())));
        Gtpv2Message rsp = gtp.of(SendGtpv2Message.class).get(0).message();
        assertEquals(PgwS5cProcedure.CAUSE_PCRF_REJECTED, GtpcShapes.cause(rsp));
        assertEquals(0, ipam.size(), "UE address released");
        assertTrue(pfcp.of(PfcpSessionEstablishment.class).isEmpty());
    }

    @Test
    void foreignAnswersAreNotConsumed() {
        assertEquals(false, proc.onCca(cca("unknown;gx;1", 2001L, List.of())));
        assertEquals(false, proc.onTimeout(new DiameterTimeoutEvent("x", 16_777_251L, 316)));
    }
}
