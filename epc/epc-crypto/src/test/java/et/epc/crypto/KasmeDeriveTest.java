package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import et.epc.api.PlmnId;
import org.junit.jupiter.api.Test;

class KasmeDeriveTest {

    private static final byte[] CK = Bytes.parseHex("b40ba9a3c58b2a05bbf0d987b21bf8cb");
    private static final byte[] IK = Bytes.parseHex("f769bcd751044604127672711c6d3441");

    @Test
    void deriveIs32BytesAndDeterministic() {
        byte[] sqn = Bytes.parseHex("aa689c648370");
        byte[] a = Kasme.derive(CK, IK, new PlmnId("001", "01"), sqn);
        byte[] b = Kasme.derive(CK, IK, new PlmnId("001", "01"), sqn);
        assertEquals(32, a.length);
        assertArrayEquals(a, b);
    }

    @Test
    void deriveDependsOnPlmn() {
        byte[] sqn = Bytes.parseHex("aa689c648370");
        byte[] a = Kasme.derive(CK, IK, new PlmnId("001", "01"), sqn);
        byte[] b = Kasme.derive(CK, IK, new PlmnId("724", "10"), sqn);
        assertNotEquals(Bytes.toHex(a), Bytes.toHex(b));
    }

    @Test
    void deriveDependsOnSqnXorAk() {
        byte[] a = Kasme.derive(CK, IK, new PlmnId("001", "01"), Bytes.parseHex("aa689c648370"));
        byte[] b = Kasme.derive(CK, IK, new PlmnId("001", "01"), Bytes.parseHex("bb689c648370"));
        assertNotEquals(Bytes.toHex(a), Bytes.toHex(b));
    }

    @Test
    void deriveDependsOnKeyMaterial() {
        byte[] sqn = Bytes.parseHex("aa689c648370");
        byte[] a = Kasme.derive(CK, IK, new PlmnId("001", "01"), sqn);
        byte[] flippedCk = CK.clone();
        flippedCk[0] ^= 0x01;
        byte[] b = Kasme.derive(flippedCk, IK, new PlmnId("001", "01"), sqn);
        assertNotEquals(Bytes.toHex(a), Bytes.toHex(b));
    }
}