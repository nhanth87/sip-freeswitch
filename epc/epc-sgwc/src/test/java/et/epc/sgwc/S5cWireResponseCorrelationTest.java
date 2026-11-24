package et.epc.sgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * S5-C is two-phase: the S11 Create Session goes out as an S5-C request and the SBB thread is
 * released; the S5-C response (another SLEE event) completes it. The old version blocked the SBB
 * thread for the answer — on the wire the router could never deliver it (5 s timeout, cause 73).
 */
class S5cWireResponseCorrelationTest {

    private static final InetSocketAddress PGWC_PEER = new InetSocketAddress("192.0.2.70", 2123);
    private static final InetSocketAddress MME = new InetSocketAddress("192.0.2.9", 2123);

    private final List<SendGtpv2Message> sent = new ArrayList<>();
    private HeapSessionStore store;
    private SgwControl sgw;

    private SgwControl wired(boolean wire) throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-sgwc-1");
        store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress sgwu = new InetSocketAddress("192.0.2.40", 8805);
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, sgwu);
        RaCommandPort gtp = cmd -> sent.add((SendGtpv2Message) cmd);
        return new SgwControl(cfg, store, pgw, pfcp, local, local, sgwu, gtp, wire, PGWC_PEER);
    }

    private static SgwControl.S11Context s11(int seq) {
        return new SgwControl.S11Context(MME, seq, (byte) 1,
                new Fteid(0xA001, ip("192.0.2.9"), GtpcShapes.IF_S11_MME), 5, null, List.of());
    }

    @Test
    void createSessionReturnsAtOnceAndTheResponseCompletesIt() throws Exception {
        sgw = wired(true);
        var started = sgw.beginCreateSession(new Imsi("001010000000001"), ApnConfig.internet(), s11(4), null);
        assertTrue(started.isEmpty(), "wire mode never answers S11 before S5-C does");
        assertEquals(1, sent.size());
        Gtpv2Message req = sent.get(0).message();
        assertEquals(PGWC_PEER, sent.get(0).peer());
        Fteid sgwS5c = GtpcShapes.fteidAt(req, 0);

        SgwControl.CreateOutcome o = sgw.onS5cCreateResponse(response(req, sgwS5c.teid())).orElseThrow();
        assertTrue(o.accepted());
        assertEquals(4, o.s11().sequence(), "answers the MME's own transaction");
        SgwCreateSessionResult r = o.result();
        assertEquals("10.64.0.10", r.ue().ipv4().getHostAddress());
        assertEquals(GtpcShapes.IF_S5C_PGW, r.pgw().pgwS5c().interfaceType());
        assertEquals(0x7777, r.pgw().pgwS5c().teid());
        assertEquals(0x8888, r.pgw().pgwS5u().teid());
        assertEquals(GtpcShapes.IF_S11_SGW, r.sgwS11c().interfaceType());
        assertEquals(GtpcShapes.IF_S1U_SGW, r.sgwS1u().interfaceType());
        assertEquals(0xA001, sgw.mmeS11(r.sgwS11c().teid()).teid());
        SessionCheckpoint cp = store.findByS11cTeid(r.sgwS11c().teid()).orElseThrow();
        assertEquals(0x8888, cp.pgwS5u().teid());
        assertEquals(1, store.size());
    }

    @Test
    void aRejectedS5LegRejectsTheS11Request() throws Exception {
        sgw = wired(true);
        sgw.beginCreateSession(new Imsi("001010000000001"), ApnConfig.internet(), s11(5), null);
        Gtpv2Message req = sent.get(0).message();
        Gtpv2Message reject = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                GtpcShapes.fteidAt(req, 0).teid(), req.sequence(), (byte) 1, List.of(Gtpv2Ies.cause(73)));
        SgwControl.CreateOutcome o = sgw.onS5cCreateResponse(reject).orElseThrow();
        assertFalse(o.accepted());
        assertEquals(73, o.cause());
        assertEquals(0, store.size());
    }

    @Test
    void anUnansweredS5LegIsRemotePeerNotResponding() throws Exception {
        sgw = wired(true);
        sgw.beginCreateSession(new Imsi("001010000000001"), ApnConfig.internet(), s11(6), null);
        int seq = sent.get(0).message().sequence();
        var o = (SgwControl.CreateOutcome) sgw.onS5cTimeout(seq).orElseThrow();
        assertEquals(GtpcShapes.CAUSE_REMOTE_PEER_NOT_RESPONDING, o.cause());
        assertTrue(sgw.onS5cTimeout(seq).isEmpty(), "answered once");
    }

    @Test
    void deleteSessionGoesToThePgwOnItsS5Teid() throws Exception {
        sgw = wired(true);
        sgw.beginCreateSession(new Imsi("001010000000001"), ApnConfig.internet(), s11(7), null);
        Gtpv2Message req = sent.get(0).message();
        int sgwS5cTeid = GtpcShapes.fteidAt(req, 0).teid();
        SgwCreateSessionResult r = sgw.onS5cCreateResponse(response(req, sgwS5cTeid)).orElseThrow().result();

        assertTrue(sgw.beginDeleteSession(r.sgwS11c().teid(), s11(8)).isEmpty());
        Gtpv2Message dsr = sent.get(1).message();
        assertEquals(Gtpv2MessageType.DELETE_SESSION_REQUEST, dsr.type());
        assertEquals(0x7777, dsr.teid(), "header TEID = PGW S5-C TEID");
        Gtpv2Message dsResp = new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_RESPONSE, sgwS5cTeid,
                dsr.sequence(), (byte) 1, List.of(Gtpv2Ies.causeAccepted()));
        SgwControl.DeleteOutcome d = sgw.onS5cDeleteResponse(dsResp).orElseThrow();
        assertEquals(16, d.cause());
        assertEquals(0xA001, d.mmeS11().teid());
        assertEquals(0, store.size());
    }

    @Test
    void wireDisabledAnswersInProcess() throws Exception {
        sgw = wired(false);
        SgwControl.CreateOutcome o = sgw.beginCreateSession(
                new Imsi("001010000000001"), ApnConfig.internet(), s11(1), null).orElseThrow();
        assertTrue(o.accepted());
        assertTrue(sent.isEmpty(), "no S5-C wire when epc.s5c.wire=false");
        assertEquals(2, store.size(), "SGW + PGW checkpoints in-process");
    }

    @Test
    void mmeProvidedPgwFteidOverridesConfiguredPeer() throws Exception {
        sgw = wired(true);
        Fteid mmePgwS5c = new Fteid(0x7777, ip("192.0.2.99"), GtpcShapes.IF_S5C_PGW);
        sgw.beginCreateSession(new Imsi("001010000000001"), ApnConfig.internet(), s11(2), mmePgwS5c);
        assertEquals(ip("192.0.2.99"), sent.get(0).peer().getAddress());
        assertEquals(PGWC_PEER.getPort(), sent.get(0).peer().getPort());
    }

    /** A TS 29.274 S5-C Create Session Response: header TEID = the SGW's S5-C TEID. */
    private static Gtpv2Message response(Gtpv2Message req, int sgwS5cTeid) {
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                sgwS5cTeid, req.sequence(), req.recovery(),
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        GtpcShapes.fteid(0, new Fteid(0x7777, ip("192.0.2.60"), GtpcShapes.IF_S5C_PGW)),
                        new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(ip("10.64.0.10"))),
                        GtpcShapes.bearerContext(5, 16, List.of(GtpcShapes.fteid(2,
                                new Fteid(0x8888, ip("192.0.2.61"), GtpcShapes.IF_S5U_PGW))))));
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