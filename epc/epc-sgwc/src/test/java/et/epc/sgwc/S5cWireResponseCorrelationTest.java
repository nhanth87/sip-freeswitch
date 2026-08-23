package et.epc.sgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.api.cluster.SessionCheckpoint;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S5-C request/response correlation: SgwControl blocks for the PGW-C reply. */
class S5cWireResponseCorrelationTest {

    private static final InetSocketAddress PGWC_PEER = new InetSocketAddress("192.0.2.70", 2123);

    @Test
    void createSessionSendsS5cRequestAndCompletesOnResponse() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-sgwc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress sgwu = new InetSocketAddress("192.0.2.40", 8805);

        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store,
                cmd -> {}, local, sgwu);

        List<SendGtpv2Message> sent = new ArrayList<>();
        SgwControl[] holder = new SgwControl[1];
        RaCommandPort gtp = cmd -> {
            SendGtpv2Message s = (SendGtpv2Message) cmd;
            sent.add(s);
            holder[0].onS5cResponse(response(s.message()));
        };
        holder[0] = new SgwControl(cfg, store, pgw, pfcp, local, local, sgwu, gtp, true, PGWC_PEER);

        Fteid enb = new Fteid(0x1111, InetAddress.getByName("192.0.2.50"), 0);
        SgwCreateSessionResult r = holder[0].createSession(
                new Imsi("001010000000001"), ApnConfig.internet(), enb);

        assertEquals(1, sent.size());
        assertEquals(Gtpv2MessageType.CREATE_SESSION_REQUEST, sent.get(0).message().type());
        assertEquals(PGWC_PEER, sent.get(0).peer());

        assertNotNull(r.ue().ipv4());
        assertEquals("10.64.0.10", r.ue().ipv4().getHostAddress());
        assertEquals(6, r.pgw().pgwS5c().interfaceType());
        assertEquals(0x7777, r.pgw().pgwS5c().teid());
        assertEquals(0x8888, r.pgw().pgwS5u().teid());

        SessionCheckpoint cp = store.findByS11cTeid(r.sgwS11c().teid()).orElseThrow();
        assertNotNull(cp.pgwS5uKey());
        assertTrue(cp.pgwS5uKey().startsWith(Integer.toUnsignedString(0x8888)));
        // S5-C does not carry the PCEF charging id — the SGW checkpoint stores 0.
        assertEquals(0L, cp.chargingId());
        assertEquals(1, store.size());
    }

    @Test
    void wireDisabledFallsBackToInProcessPgw() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-sgwc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress sgwu = new InetSocketAddress("192.0.2.40", 8805);

        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, sgwu);
        RaCommandPort gtp = cmd -> {
            throw new AssertionError("wire must not be used when epc.s5c.wire=false");
        };
        SgwControl sgw = new SgwControl(cfg, store, pgw, pfcp, local, local, sgwu,
                gtp, false, PGWC_PEER);

        Fteid enb = new Fteid(0x1111, InetAddress.getByName("192.0.2.50"), 0);
        SgwCreateSessionResult r = sgw.createSession(
                new Imsi("001010000000001"), ApnConfig.internet(), enb);

        assertNotNull(r.ue().ipv4());
        assertEquals(1L, r.pgw().chargingId().value());
        // SGW + PGW checkpoints are both present for the in-process path.
        assertEquals(2, store.size());
    }

    @Test
    void mmeProvidedPgwFteidOverridesConfiguredPeer() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-sgwc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress sgwu = new InetSocketAddress("192.0.2.40", 8805);

        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store,
                cmd -> {}, local, sgwu);

        List<SendGtpv2Message> sent = new ArrayList<>();
        SgwControl[] holder = new SgwControl[1];
        RaCommandPort gtp = cmd -> {
            SendGtpv2Message s = (SendGtpv2Message) cmd;
            sent.add(s);
            holder[0].onS5cResponse(response(s.message()));
        };
        holder[0] = new SgwControl(cfg, store, pgw, pfcp, local, local, sgwu, gtp, true, PGWC_PEER);

        Fteid enb = new Fteid(0x1111, InetAddress.getByName("192.0.2.50"), 0);
        Fteid mmePgwS5c = new Fteid(0x7777, InetAddress.getByName("192.0.2.99"), 6);
        holder[0].createSession(new Imsi("001010000000001"), ApnConfig.internet(), enb, mmePgwS5c);

        assertEquals(1, sent.size());
        assertEquals(InetAddress.getByName("192.0.2.99"), sent.get(0).peer().getAddress());
        assertEquals(PGWC_PEER.getPort(), sent.get(0).peer().getPort());
    }

    private static Gtpv2Message response(Gtpv2Message req) {
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                req.teid(), req.sequence(), req.recovery(),
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x7777, ip("192.0.2.60"), 6))),
                        new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(ip("10.64.0.10"))),
                        new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, Gtpv2Ies.encodeBearerContext(
                                Gtpv2Ies.DEFAULT_BEARER_EBI,
                                new GtpFteid(0x8888, ip("192.0.2.61"), 5),
                                Gtpv2Ies.causeAccepted()))));
    }

    private static InetAddress ip(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
}