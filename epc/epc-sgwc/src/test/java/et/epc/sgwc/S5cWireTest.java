package et.epc.sgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Codec;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** S5-C wire encode/decode round-trips through the vendor {@code ra-gtpv2c} helpers. */
class S5cWireTest {

    @Test
    void createSessionRequestRoundTripsThroughCodec() throws Exception {
        Imsi imsi = new Imsi("001010000000001");
        Fteid sgwS5u = new Fteid(0x2222, InetAddress.getByName("192.0.2.30"), 4);

        Gtpv2Message req = SgwControl.buildS5cCreateSessionRequest(imsi, ApnConfig.internet(), sgwS5u, 7);
        Gtpv2Message decoded = Gtpv2Codec.decode(Gtpv2Codec.encode(req));

        assertEquals(Gtpv2MessageType.CREATE_SESSION_REQUEST, decoded.type());
        assertEquals(7, decoded.sequence());
        assertEquals("001010000000001", Gtpv2Ies.imsiDigits(decoded));
        assertEquals("internet", Gtpv2Ies.apn(decoded));

        GtpFteid s5u = Gtpv2Ies.senderFteid(decoded);
        assertEquals(sgwS5u.teid(), s5u.teid());
        assertEquals(sgwS5u.address(), s5u.address());
        assertEquals(4, s5u.interfaceType());
    }

    @Test
    void createSessionResponseRoundTripsThroughCodec() throws Exception {
        InetAddress pgwS5c = InetAddress.getByName("192.0.2.60");
        InetAddress pgwS5u = InetAddress.getByName("192.0.2.61");
        InetAddress ue = InetAddress.getByName("10.64.0.10");

        Gtpv2Message rsp = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, 0, 7, (byte) 1,
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x7777, pgwS5c, 6))),
                        new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(ue)),
                        new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, Gtpv2Ies.encodeBearerContext(
                                Gtpv2Ies.DEFAULT_BEARER_EBI,
                                new GtpFteid(0x8888, pgwS5u, 5),
                                Gtpv2Ies.causeAccepted()))));

        Gtpv2Message wire = Gtpv2Codec.decode(Gtpv2Codec.encode(rsp));
        S5cSessionResult decoded = SgwControl.decodeS5cResponse(wire);

        assertEquals(ue, decoded.ue().ipv4());
        assertEquals(6, decoded.pgwS5c().interfaceType());
        assertEquals(0x7777, decoded.pgwS5c().teid());
        assertEquals(5, decoded.pgwS5u().interfaceType());
        assertEquals(0x8888, decoded.pgwS5u().teid());
        assertEquals(pgwS5u, decoded.pgwS5u().address());
        assertNotNull(wire.first(Gtpv2Ie.CAUSE));
        assertEquals(Gtpv2Ies.CAUSE_REQUEST_ACCEPTED, wire.first(Gtpv2Ie.CAUSE).value()[0] & 0xff);
    }

    @Test
    void pgwS5cFteidDecodesMmeProvidedControlPlaneFteid() throws Exception {
        InetAddress pgwS5cAddr = InetAddress.getByName("192.0.2.99");
        Gtpv2Message req = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 1, (byte) 1,
                List.of(
                        new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd("001010000000001")),
                        new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn("internet")),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x1111, InetAddress.getByName("192.0.2.10"), 9))),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 1, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x7777, pgwS5cAddr, 6)))));

        GtpFteid selected = Gtpv2Ies.pgwS5cFteid(req);
        assertEquals(pgwS5cAddr, selected.address());
        assertEquals(0x7777, selected.teid());

        Gtpv2Message noPgw = new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, 1, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                        new GtpFteid(0x1111, InetAddress.getByName("192.0.2.10"), 9)))));
        assertEquals(null, Gtpv2Ies.pgwS5cFteid(noPgw));
    }
}