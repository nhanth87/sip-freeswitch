package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.host.sbb.GtpGatewaySbb;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import et.epc.sgwc.SgwControl;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GTP-C SBB procedures driven directly, without a SLEE container. */
class GtpGatewaySbbTest {

    private static final InetSocketAddress MME = new InetSocketAddress("192.0.2.10", 2123);

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort gtpPort = sent::add;

    private GtpGatewaySbb.$Concrete sbb;

    @BeforeEach
    void setUp() {
        sbb = new GtpGatewaySbb.$Concrete(null, null, null);
        inject(sbb, "gtpRa", gtpPort);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = GtpGatewaySbb.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SgwControl sgw() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-sgwc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress up = new InetSocketAddress("192.0.2.40", 8805);
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, up);
        return new SgwControl(cfg, store, pgw, pfcp, local, local, up);
    }

    private static RaBootstrapPort noop() {
        return new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
            }
        };
    }

    private static Gtpv2Message csr(int seq) throws Exception {
        InetAddress enb = InetAddress.getByName("192.0.2.50");
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, seq, (byte) 1,
                List.of(
                        new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000001")),
                        new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn("internet")),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x1111, enb, 0)))));
    }

    private static Gtpv2Message mbr(int s11cTeid, int seq, int enbTeid) throws Exception {
        byte[] bc = Gtpv2Ies.encodeBearerContext((byte) 5,
                new GtpFteid(enbTeid, InetAddress.getByName("192.0.2.51"), 0),
                Gtpv2Ies.causeAccepted());
        return new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_REQUEST, s11cTeid, seq, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, bc)));
    }

    private Gtpv2Message last() {
        assertTrue(!sent.isEmpty(), "expected an outbound GTP message");
        return ((SendGtpv2Message) sent.get(sent.size() - 1)).message();
    }

    private int lastCause() {
        return last().first(Gtpv2Ie.CAUSE).value()[0] & 0xff;
    }

    @Test
    void createSessionWithSgwAnswersWithBearerAndStoresCmp() throws Exception {
        sbb = new GtpGatewaySbb.$Concrete(sgw(), null, null);
        inject(sbb, "gtpRa", gtpPort);
        sbb.onEvent(new Gtpv2MessageEvent(csr(7), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.CREATE_SESSION_RESPONSE, rsp.type());
        assertEquals(7, rsp.sequence());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
        assertNotNull(rsp.first(Gtpv2Ie.FTEID));
        assertNotNull(rsp.first(Gtpv2Ie.PDN_ADDRESS));
        assertNotNull(rsp.first(Gtpv2Ie.BEARER_CONTEXT));
        assertEquals("001010000000001", sbb.getImsi());
        assertEquals("internet", sbb.getApn());
        // W3-b: internet APN carries no PCO.
        assertEquals(null, rsp.first(Gtpv2Ie.PCO));
        assertNotNull(sbb.getS11cTeid());
        assertNotNull(sbb.getPfcpSeid());
    }

    @Test
    void imsApnCreateSessionResponseCarriesPcscfPco() throws Exception {
        et.epc.api.PcoDefaults.configure("172.31.255.10", "10.10.0.53");
        try {
            sbb = new GtpGatewaySbb.$Concrete(sgw(), null, null);
            inject(sbb, "gtpRa", gtpPort);
            Gtpv2Message req = new Gtpv2Message(
                    Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 11, (byte) 1,
                    List.of(
                            new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000001")),
                            new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn("ims")),
                            new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                    new GtpFteid(0x1111, InetAddress.getByName("192.0.2.50"), 0)))));
            sbb.onEvent(new Gtpv2MessageEvent(req, MME), null);
            Gtpv2Message rsp = last();
            Gtpv2Ie pcoIe = rsp.first(Gtpv2Ie.PCO);
            assertNotNull(pcoIe, "IMS APN CSR must carry message-level PCO (IE 78)");
            List<Gtpv2Ies.PcoContainer> pco = Gtpv2Ies.decodePcoValue(pcoIe.value());
            assertEquals(InetAddress.getByName("172.31.255.10"),
                    Gtpv2Ies.pcoIpv4(pco, Gtpv2Ies.PCO_P_CSCF_IPV4));
            assertEquals(InetAddress.getByName("10.10.0.53"),
                    Gtpv2Ies.pcoIpv4(pco, Gtpv2Ies.PCO_DNS_SERVER_IPV4));
        } finally {
            et.epc.api.PcoDefaults.configure(null, null);
        }
    }

    @Test
    void createSessionWithNeitherRoleAnswersCause73() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(csr(3), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.CREATE_SESSION_RESPONSE, rsp.type());
        assertEquals(73, lastCause());
    }

    @Test
    void modifyBearerWithSgwReprogramsAndAnswers() throws Exception {
        sbb = new GtpGatewaySbb.$Concrete(sgw(), null, null);
        inject(sbb, "gtpRa", gtpPort);
        sbb.onEvent(new Gtpv2MessageEvent(csr(7), MME), null);
        int s11c = sbb.getS11cTeid();
        sent.clear();
        sbb.onEvent(new Gtpv2MessageEvent(mbr(s11c, 8, 0x3333), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.MODIFY_BEARER_RESPONSE, rsp.type());
        assertEquals(8, rsp.sequence());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
        assertNotNull(rsp.first(Gtpv2Ie.BEARER_CONTEXT));
    }

    @Test
    void modifyBearerWithoutSgwAnswersCause64() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(mbr(0x1111, 5, 0x2222), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.MODIFY_BEARER_RESPONSE, rsp.type());
        assertEquals(64, lastCause());
    }

    @Test
    void deleteSessionAnswersAccepted() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(
                new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_REQUEST, 0, 9, (byte) 1, List.of()), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.DELETE_SESSION_RESPONSE, rsp.type());
        assertEquals(9, rsp.sequence());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
    }

    @Test
    void releaseAccessBearersWithSgwAnswersAccepted() throws Exception {
        sbb = new GtpGatewaySbb.$Concrete(sgw(), null, null);
        inject(sbb, "gtpRa", gtpPort);
        sbb.onEvent(new Gtpv2MessageEvent(csr(7), MME), null);
        int s11c = sbb.getS11cTeid();
        sent.clear();
        sbb.onEvent(new Gtpv2MessageEvent(new Gtpv2Message(
                Gtpv2MessageType.RELEASE_ACCESS_BEARERS_REQUEST, s11c, 10, (byte) 1, List.of()), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE, rsp.type());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
    }

    @Test
    void releaseAccessBearersWithoutSgwAnswersContextNotFound() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(new Gtpv2Message(
                Gtpv2MessageType.RELEASE_ACCESS_BEARERS_REQUEST, 0xdead, 6, (byte) 1, List.of()), MME), null);
        Gtpv2Message rsp = last();
        assertEquals(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE, rsp.type());
        assertEquals(Gtpv2Ies.CAUSE_CONTEXT_NOT_FOUND, lastCause());
    }

    @Test
    void inboundDdnIsAcknowledged() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(new Gtpv2Message(
                Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION, 0, 4, (byte) 1, List.of()), MME), null);
        Gtpv2Message ack = last();
        assertEquals(Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE, ack.type());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, lastCause());
    }

    @Test
    void ddnAckProducesNoResponse() throws Exception {
        sbb.onEvent(new Gtpv2MessageEvent(new Gtpv2Message(
                Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE, 0, 4, (byte) 1, List.of()), MME), null);
        assertTrue(sent.isEmpty());
    }

    @Test
    void pfcpEventStoresSeid() throws Exception {
        sbb.onEvent(new PfcpMessageEvent(PfcpMessage.heartbeatRequest(1), MME), null);
        assertEquals(0L, sbb.getPfcpSeid());
    }

    @Test
    void unknownEventIsIgnored() throws Exception {
        sbb.onEvent(new SleeEvent() {}, null);
        assertTrue(sent.isEmpty());
    }

    @Test
    void malformedCreateSessionAnswersCause73() throws Exception {
        Gtpv2Message bare = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 2, (byte) 1, List.of());
        sbb.onEvent(new Gtpv2MessageEvent(bare, MME), null);
        assertEquals(73, lastCause());
    }
}