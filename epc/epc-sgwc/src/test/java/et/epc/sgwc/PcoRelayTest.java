package et.epc.sgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W3-b — split-mode PCO relay seam: the PGW-C Create Session Response carries
 * IE 78, {@link SgwControl#decodeS5cResponse} captures it, and the SGW-C
 * re-emits it verbatim toward the MME.
 */
class PcoRelayTest {

    @Test
    void s5cResponsePcoIsCapturedForMmeRelay() throws Exception {
        InetAddress pcscf = InetAddress.getByName("172.31.255.10");
        InetAddress pgw = InetAddress.getByName("192.0.2.60");
        byte[] bearer = Gtpv2Ies.encodeBearerContext((byte) 5,
                new GtpFteid(0x55aa, pgw, 5), Gtpv2Ies.causeAccepted());
        Gtpv2Message s5cRsp = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                0x1234, 42, (byte) 1,
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x77b, pgw, 6))),
                        new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(
                                InetAddress.getByName("10.64.0.7"))),
                        new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0, bearer),
                        Gtpv2Ies.pco(Gtpv2Ies.ipv4Container(
                                Gtpv2Ies.PCO_P_CSCF_IPV4, pcscf))));

        // Full wire round trip first — the MME-facing hop must survive the codec.
        Gtpv2Message decoded = com.microjainslee.ra.gtpv2c.Gtpv2Codec.decode(
                com.microjainslee.ra.gtpv2c.Gtpv2Codec.encode(s5cRsp));

        S5cSessionResult r = SgwControl.decodeS5cResponse(decoded);
        assertEquals(1, r.pco().size());
        assertEquals(pcscf, Gtpv2Ies.pcoIpv4(r.pco(), Gtpv2Ies.PCO_P_CSCF_IPV4));
    }

    @Test
    void s5cResponseWithoutPcoYieldsEmptyRelay() throws Exception {
        InetAddress pgw = InetAddress.getByName("192.0.2.61");
        Gtpv2Message s5cRsp = new Gtpv2Message(
                Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                0x1234, 43, (byte) 1,
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(
                                new GtpFteid(0x77c, pgw, 6)))));
        S5cSessionResult r = SgwControl.decodeS5cResponse(s5cRsp);
        assertEquals(List.of(), r.pco());
    }
}
