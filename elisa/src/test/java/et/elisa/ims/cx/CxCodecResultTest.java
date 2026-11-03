package et.elisa.ims.cx;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** TS 29.229 result handling: 3GPP outcomes arrive as Experimental-Result-Code. */
class CxCodecResultTest {

    @Test
    void uaaAcceptsFirstSubsequentAndUnregisteredService() {
        assertTrue(CxCodec.uaaSuccess(Map.of(CxCodec.RESULT_CODE, "2001")));
        assertTrue(CxCodec.uaaSuccess(Map.of(CxCodec.RESULT_CODE, "2002")));
        assertTrue(CxCodec.uaaSuccess(Map.of(CxCodec.RESULT_CODE, "2003")));
        assertFalse(CxCodec.uaaSuccess(Map.of(CxCodec.RESULT_CODE, "5001")), "ERROR_USER_UNKNOWN");
        assertFalse(CxCodec.uaaSuccess(Map.of()));
    }

    @Test
    void liaAcceptsSuccessAndUnregisteredService() {
        assertTrue(CxCodec.liaSuccess(Map.of(CxCodec.RESULT_CODE, "2001")));
        assertTrue(CxCodec.liaSuccess(Map.of(CxCodec.RESULT_CODE, "2003")));
        assertFalse(CxCodec.liaSuccess(Map.of(CxCodec.RESULT_CODE, "2002")));
        assertFalse(CxCodec.liaSuccess(Map.of(CxCodec.RESULT_CODE, "5001")));
    }

    @Test
    void requestsGoOutAsTyped3gppAvps() {
        var lir = CxCodec.toWire(CxCodec.lirAvps("sip:1000@ims.lab"));
        var impu = lir.stream().filter(a -> a.code() == CxCodec.PUBLIC_IDENTITY).findFirst().orElseThrow();
        assertTrue(impu.vendorId() == 10_415L && impu.mandatory(), "Public-Identity: 3GPP vendor, M-bit");
        assertTrue(lir.stream().anyMatch(a -> a.code() == 260), "Vendor-Specific-Application-Id");
        assertTrue(lir.stream().anyMatch(a -> a.code() == 277), "Auth-Session-State");

        var mar = CxCodec.toWire(CxCodec.marAvps("a@ims.lab", "sip:a@ims.lab", "sip:scscf:5060"));
        var item = mar.stream().filter(a -> a.code() == CxCodec.SIP_AUTH_DATA_ITEM).findFirst().orElseThrow();
        assertTrue(item.children().stream().anyMatch(c -> c.code() == CxCodec.SIP_AUTHENTICATION_SCHEME),
                "scheme inside SIP-Auth-Data-Item");
        var user = mar.stream().filter(a -> a.code() == CxCodec.USER_NAME).findFirst().orElseThrow();
        assertTrue(user.vendorId() == 0L, "User-Name is a base AVP");

        var sar = CxCodec.toWire(CxCodec.sarAvps("a@ims.lab", "sip:a@ims.lab", "sip:scscf:5060"));
        assertTrue(sar.stream().anyMatch(a -> a.code() == CxCodec.SERVER_ASSIGNMENT_TYPE && a.asUnsigned32() == 1L));
    }
}
