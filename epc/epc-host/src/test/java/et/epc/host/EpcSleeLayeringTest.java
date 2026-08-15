package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.Fteid;
import et.epc.api.cluster.EpcClusterConfig;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Codec;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EpcSleeLayeringTest {

    private MicroSleeContainer slee;
    private EpcBootstrap bootstrap;

    @AfterEach
    void tearDown() {
        if (bootstrap != null) {
            bootstrap.stop();
        }
        if (slee != null && slee.getState() == MicroSleeContainer.State.STARTED) {
            slee.stop();
        }
    }

    @Test
    void echoStaysInRaCreateSessionGoesToSbb() throws Exception {
        slee = new MicroSleeContainer();
        bootstrap = new EpcBootstrap(
                new EpcRoles(false, true, false), EpcClusterConfig.local("epc-sgwc-1"));
        bootstrap.attachTo(slee);
        CopyOnWriteArrayList<byte[]> sent = new CopyOnWriteArrayList<>();
        bootstrap.gtp().setSink((bytes, peer) -> sent.add(bytes));
        InetSocketAddress mme = new InetSocketAddress("192.0.2.10", 2123);

        bootstrap.gtp().receive(Gtpv2Codec.encode(Gtpv2Message.echoRequest(1, (byte) 9)), mme);
        assertEquals(Gtpv2MessageType.ECHO_RESPONSE, waitDecode(sent, 1).type());
        assertTrue(bootstrap.sessions().size() == 0);

        Fteid enb = new Fteid(0x1111, InetAddress.getByName("192.0.2.50"), 0);
        Gtpv2Message csr = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 7, (byte) 1,
                List.of(
                        new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000001")),
                        new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn("internet")),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(enb.teid(), enb.address(), enb.interfaceType())))));
        bootstrap.gtp().receive(Gtpv2Codec.encode(csr), mme);
        Gtpv2Message rsp = waitDecode(sent, 2);
        assertEquals(Gtpv2MessageType.CREATE_SESSION_RESPONSE, rsp.type());
        assertEquals(7, rsp.sequence());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, rsp.first(Gtpv2Ie.CAUSE).value()[0] & 0xff);
        assertNotNull(rsp.first(Gtpv2Ie.FTEID));
        Gtpv2Ie bearer = rsp.first(Gtpv2Ie.BEARER_CONTEXT);
        assertNotNull(bearer);
        List<Gtpv2Ie> inner = Gtpv2Ies.decodeGrouped(bearer.value());
        assertTrue(inner.stream().anyMatch(i -> i.type() == Gtpv2Ie.EBI && (i.value()[0] & 0xff) == 5));
        assertTrue(inner.stream().anyMatch(i -> i.type() == Gtpv2Ie.FTEID));
        GtpFteid s1u = Gtpv2Ies.s1uFromBearerContext(rsp);
        assertNotNull(s1u);

        int sgwS11 = Gtpv2Ies.decodeFteid(rsp.first(Gtpv2Ie.FTEID).value()).teid();
        Gtpv2Message rab = new Gtpv2Message(
                Gtpv2MessageType.RELEASE_ACCESS_BEARERS_REQUEST, sgwS11, 8, (byte) 1, List.of());
        bootstrap.gtp().receive(Gtpv2Codec.encode(rab), mme);
        Gtpv2Message rabRsp = waitDecode(sent, 3);
        assertEquals(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE, rabRsp.type());
        assertEquals(8, rabRsp.sequence());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, rabRsp.first(Gtpv2Ie.CAUSE).value()[0] & 0xff);
        assertTrue(bootstrap.sessions().size() >= 1);
        assertNotNull(bootstrap.gtp());
        assertTrue(bootstrap.slee() == slee);
    }

    @Test
    void releaseAccessUnknownTeidAnswersContextNotFound() throws Exception {
        slee = new MicroSleeContainer();
        bootstrap = new EpcBootstrap(
                new EpcRoles(false, true, false), EpcClusterConfig.local("epc-sgwc-1"));
        bootstrap.attachTo(slee);
        CopyOnWriteArrayList<byte[]> sent = new CopyOnWriteArrayList<>();
        bootstrap.gtp().setSink((bytes, peer) -> sent.add(bytes));
        InetSocketAddress mme = new InetSocketAddress("192.0.2.10", 2123);
        Gtpv2Message rab = new Gtpv2Message(
                Gtpv2MessageType.RELEASE_ACCESS_BEARERS_REQUEST, 0xdead, 3, (byte) 1, List.of());
        bootstrap.gtp().receive(Gtpv2Codec.encode(rab), mme);
        Gtpv2Message rsp = waitDecode(sent, 1);
        assertEquals(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE, rsp.type());
        assertEquals(Gtpv2Ies.CAUSE_CONTEXT_NOT_FOUND, rsp.first(Gtpv2Ie.CAUSE).value()[0] & 0xff);
    }

    @Test
    void inboundDdnIsAcknowledged() throws Exception {
        slee = new MicroSleeContainer();
        bootstrap = new EpcBootstrap(
                new EpcRoles(false, true, false), EpcClusterConfig.local("epc-sgwc-1"));
        bootstrap.attachTo(slee);
        CopyOnWriteArrayList<byte[]> sent = new CopyOnWriteArrayList<>();
        bootstrap.gtp().setSink((bytes, peer) -> sent.add(bytes));
        InetSocketAddress mme = new InetSocketAddress("192.0.2.10", 2123);
        Gtpv2Message ddn = new Gtpv2Message(
                Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION, 0, 4, (byte) 1, List.of());
        bootstrap.gtp().receive(Gtpv2Codec.encode(ddn), mme);
        Gtpv2Message ack = waitDecode(sent, 1);
        assertEquals(Gtpv2MessageType.DOWNLINK_DATA_NOTIFICATION_ACKNOWLEDGE, ack.type());
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, ack.first(Gtpv2Ie.CAUSE).value()[0] & 0xff);
    }

    private static Gtpv2Message waitDecode(CopyOnWriteArrayList<byte[]> sent, int min) throws InterruptedException {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (sent.size() < min && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(sent.size() >= min, "expected " + min + " GTP writes, got " + sent.size());
        return Gtpv2Codec.decode(sent.get(min - 1));
    }
}
