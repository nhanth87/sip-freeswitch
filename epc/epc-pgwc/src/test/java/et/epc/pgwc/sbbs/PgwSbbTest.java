package et.epc.pgwc.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.PfcpCodec;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpMessageType;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.BearerId;
import et.epc.api.Imsi;
import et.epc.api.SessionKeys;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.api.cluster.SessionCheckpoint;
import et.epc.pcef.Cdr;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PGW-C SBB procedures driven directly, without a SLEE container. */
class PgwSbbTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final InetSocketAddress MME = new InetSocketAddress("192.0.2.10", 2123);
    private static final InetSocketAddress PGWU = new InetSocketAddress("192.0.2.40", 8805);

    private final List<OutboundCommand> gtpSent = new ArrayList<>();
    private final RaCommandPort gtpPort = gtpSent::add;
    private final List<OutboundCommand> pfcpSent = new ArrayList<>();
    private final RaCommandPort pfcpPort = pfcpSent::add;

    private HeapSessionStore store;
    private Ipam ipam;
    private PcefService pcef;
    private PgwControl pgw;
    private PgwSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        store = new HeapSessionStore();
        ipam = new Ipam();
        pcef = new PcefService();
        pgw = new PgwControl(EpcClusterConfig.local("epc-pgw-1"), ipam, pcef, store, pfcpPort,
                InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        sbb = new PgwSbb.$Concrete(pgw);
        inject(sbb, "gtpRa", gtpPort);
        inject(sbb, "pfcpRa", pfcpPort);
        gtpSent.clear();
        pfcpSent.clear();
    }

    @Test
    void createSessionDelegatesToPgwAndStoresCmp() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(csr(7), MME), null);

        Gtpv2Message rsp = lastGtp();
        assertEquals(Gtpv2MessageType.CREATE_SESSION_RESPONSE, rsp.type());
        assertEquals(7, rsp.sequence());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
        assertNotNull(rsp.first(Gtpv2Ie.FTEID));
        assertNotNull(rsp.first(Gtpv2Ie.PDN_ADDRESS));
        assertNotNull(rsp.first(Gtpv2Ie.BEARER_CONTEXT));
        assertEquals("001010000000001", sbb.getImsi());
        assertEquals("internet", sbb.getApn());
        assertNotNull(sbb.getS5cTeid());
        assertNotNull(sbb.getPfcpSeid());

        assertEquals(1, pfcpSent.size());
        assertTrue(pfcpSent.get(0) instanceof PfcpProgramSession);
        assertEquals(1, store.size());
        SessionCheckpoint cp = store.get(SessionCheckpoint.pgwId(
                new SessionKeys(IMSI, "internet", new BearerId(5)))).orElseThrow();
        assertEquals("pgw", cp.role());
        assertEquals(1, ipam.size());
        assertEquals(0, pcef.cdrs().size());
        assertTrue(cp.chargingId() > 0);
    }

    @Test
    void deleteSessionClosesChargingAndReleasesIp() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(csr(7), MME), null);
        long chargingId = store.get(SessionCheckpoint.pgwId(
                new SessionKeys(IMSI, "internet", new BearerId(5)))).orElseThrow().chargingId();
        assertEquals(1, ipam.size());
        gtpSent.clear();
        pfcpSent.clear();

        sbb.onEvent(new Gtpv2MessageEvent(dsr(8), MME), null);

        Gtpv2Message rsp = lastGtp();
        assertEquals(Gtpv2MessageType.DELETE_SESSION_RESPONSE, rsp.type());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
        assertEquals(0, store.size());
        assertEquals(0, ipam.size());
        assertEquals(1, pcef.cdrs().size());
        assertEquals(chargingId, pcef.cdrs().get(0).chargingId().value());
    }

    @Test
    void modifyBearerPassthrough() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(mbr(0x1111, 9, 0x2222), MME), null);

        Gtpv2Message rsp = lastGtp();
        assertEquals(Gtpv2MessageType.MODIFY_BEARER_RESPONSE, rsp.type());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
        assertEquals(0, pfcpSent.size());
    }

    @Test
    void pfcpAssociationAndHeartbeatAreObservedWithoutGtpResponse() {
        sbb.onEvent(new PfcpMessageEvent(PfcpMessage.associationSetupRequest(1), MME), null);
        sbb.onEvent(new PfcpMessageEvent(PfcpMessage.heartbeatRequest(2), MME), null);
        assertTrue(gtpSent.isEmpty());
    }

    @Test
    void pfcpSessionEstablishmentStoresSeid() {
        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_ESTABLISHMENT_REQUEST, 0x1234L, 3, new byte[0]), MME), null);
        assertEquals(Long.valueOf(0x1234L), sbb.getPfcpSeid());
    }

    @Test
    void sessionReportDecodesUsageReportsAndAnswersAccepted() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(csr(7), MME), null);
        SessionCheckpoint cp = store.get(SessionCheckpoint.pgwId(
                new SessionKeys(IMSI, "internet", new BearerId(5)))).orElseThrow();
        long seid = cp.pfcpSeid();
        gtpSent.clear();
        pfcpSent.clear();

        byte[] payload = PfcpCodec.encodeUsageReports(List.of(
                new PfcpUsageReport(1, PfcpIe.UT_VOLTH, 1000, 2000, 5),
                new PfcpUsageReport(1, PfcpIe.UT_PERIO, 300, 400, 5)));
        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_REPORT_REQUEST, seid, 11, payload), PGWU), null);

        SendPfcpMessage rsp = lastPfcp();
        assertEquals(PfcpMessageType.SESSION_REPORT_RESPONSE, rsp.message().type());
        assertEquals(seid, rsp.message().seid());
        assertEquals(11, rsp.message().sequence());
        assertEquals(PfcpIe.CAUSE_REQUEST_ACCEPTED,
                rsp.message().first(PfcpIe.CAUSE).value()[0] & 0xff);
        assertEquals(PGWU, rsp.peer());

        // close charging through the GTP delete to read the accumulated CDR
        sbb.onEvent(new Gtpv2MessageEvent(dsr(8), MME), null);
        Cdr cdr = pcef.cdrs().get(0);
        assertEquals(cp.chargingId(), cdr.chargingId().value());
        assertEquals(1300, cdr.uplinkOctets());
        assertEquals(2400, cdr.downlinkOctets());
    }

    @Test
    void sessionReportUnknownSeidAnswersSessionContextNotFound() {
        byte[] payload = PfcpCodec.encodeUsageReports(List.of(
                new PfcpUsageReport(1, PfcpIe.UT_PERIO, 10, 20, 1)));
        sbb.onEvent(new PfcpMessageEvent(new PfcpMessage(
                PfcpMessageType.SESSION_REPORT_REQUEST, 0xdeadL, 12, payload), PGWU), null);

        SendPfcpMessage rsp = lastPfcp();
        assertEquals(PfcpMessageType.SESSION_REPORT_RESPONSE, rsp.message().type());
        assertEquals(0xdeadL, rsp.message().seid());
        assertEquals(12, rsp.message().sequence());
        assertEquals(65, rsp.message().first(PfcpIe.CAUSE).value()[0] & 0xff);
        assertTrue(gtpSent.isEmpty());
        assertTrue(pcef.cdrs().isEmpty());
    }

    private SendPfcpMessage lastPfcp() {
        assertTrue(!pfcpSent.isEmpty(), "expected an outbound PFCP message");
        return (SendPfcpMessage) pfcpSent.get(pfcpSent.size() - 1);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = PgwSbb.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Gtpv2Message csr(int seq) throws Exception {
        InetAddress peer = InetAddress.getByName("192.0.2.50");
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, seq, (byte) 1,
                List.of(
                        new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000001")),
                        new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn("internet")),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x1111, peer, 0)))));
    }

    private static Gtpv2Message mbr(int teid, int seq, int enbTeid) throws Exception {
        byte[] bc = Gtpv2Ies.encodeBearerContext((byte) 5,
                new GtpFteid(enbTeid, InetAddress.getByName("192.0.2.51"), 0),
                Gtpv2Ies.causeAccepted());
        return new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_REQUEST, teid, seq, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, bc)));
    }

    private static Gtpv2Message dsr(int seq) {
        return new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_REQUEST, 0, seq, (byte) 1, List.of());
    }

    private Gtpv2Message lastGtp() {
        assertTrue(!gtpSent.isEmpty(), "expected an outbound GTP message");
        return ((SendGtpv2Message) gtpSent.get(gtpSent.size() - 1)).message();
    }

    private int lastCause() {
        return lastGtp().first(Gtpv2Ie.CAUSE).value()[0] & 0xff;
    }
}