package et.elisa.ims.cx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CxCodecTest {

    @Test
    void maaContainsAuthMaterialAndSuccess() {
        Map<Integer, String> m = CxCodec.maaAvps("user@ims.lab",
                "aa".repeat(32), "bb".repeat(8), "cc".repeat(16), "dd".repeat(16));
        assertEquals("2001", m.get(CxCodec.RESULT_CODE));
        assertEquals("Digest-AKAv1-MD5", m.get(CxCodec.SIP_AUTHENTICATION_SCHEME));
        assertEquals(64, m.get(CxCodec.SIP_AUTHENTICATE).length());
    }

    @Test
    void uaaAndLiaCarryServerName() {
        assertEquals("sip:scscf.ims.lab", CxCodec.uaaAvps("sip:scscf.ims.lab").get(CxCodec.SERVER_NAME));
        assertEquals("sip:scscf.ims.lab", CxCodec.liaAvps("sip:scscf.ims.lab").get(CxCodec.SERVER_NAME));
    }

    @Test
    void marRequestsAkaScheme() {
        Map<Integer, String> m = CxCodec.marAvps("i@ims.lab", "sip:i@ims.lab", "sip:scscf");
        assertEquals("1", m.get(CxCodec.SIP_NUMBER_AUTH_ITEMS));
        assertTrue(m.get(CxCodec.SIP_AUTHENTICATION_SCHEME).contains("AKA"));
    }

    @Test
    void commandCodesMatch3gppPairing() {
        assertEquals(CxCodec.UAR, CxCodec.UAA);
        assertEquals(CxCodec.MAR, CxCodec.MAA);
        assertEquals(CxCodec.SAR, CxCodec.SAA);
        assertEquals(CxCodec.LIR, CxCodec.LIA);
        assertEquals(16777216L, CxCodec.CX_APP_ID);
    }
}
