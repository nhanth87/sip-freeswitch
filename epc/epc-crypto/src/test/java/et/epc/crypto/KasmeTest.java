package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import et.epc.api.PlmnId;
import org.junit.jupiter.api.Test;

class KasmeTest {

    @Test
    void kasme_is32bytes_andDependsOnPlmn() {
        byte[] k = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] opc = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
        byte[] rand = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
        byte[] amf = Bytes.parseHex("8000");
        var v1 = EpsAka.generate(k, opc, amf, 1, new PlmnId("001", "01"), rand);
        var v2 = EpsAka.generate(k, opc, amf, 1, new PlmnId("724", "10"), rand);
        assertEquals(32, v1.kasme().length);
        assertEquals(16, v1.autn().length);
        assertNotEquals(Bytes.toHex(v1.kasme()), Bytes.toHex(v2.kasme()));
    }

    @Test
    void hmac_knownVector() {
        // RFC 4231 test case 1
        byte[] key = Bytes.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] data = "Hi There".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        String expect = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7";
        assertEquals(expect, Bytes.toHex(Kasme.hmacSha256(key, data)));
    }
}
