package et.elisa.ims.aka;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AkaMd5Test {

    @Test
    void generateAndVerifyRes() {
        byte[] ki = AkaMd5.parseHex("00112233445566778899aabbccddeeff");
        byte[] opc = AkaMd5.parseHex("000102030405060708090a0b0c0d0e0f");
        byte[] amf = AkaMd5.parseHex("8000");
        AkaMd5.Vector v = AkaMd5.generate(ki, opc, amf, 1);
        assertTrue(AkaMd5.verifyRes(v.xres(), v.xres()));
        assertFalse(AkaMd5.verifyRes(v.xres(), new byte[] {1, 2, 3}));
        String www = AkaMd5.wwwAuthenticate("ims.lab", v.rand(), v.autn());
        assertTrue(www.contains("AKAv1-MD5"));
    }
}
