package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** TS 35.208 test set 1. */
class MilenageTs35208Test {

    @Test
    void testSet1() {
        byte[] k = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] rand = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
        byte[] sqn = Bytes.parseHex("ff9bb4d0b607");
        byte[] amf = Bytes.parseHex("b9b9");
        byte[] opc = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
        Milenage.Result r = Milenage.compute(k, opc, rand, sqn, amf);
        assertEquals("4a9ffac354dfafb3", Bytes.toHex(r.macA()));
        assertEquals("a54211d5e3ba50bf", Bytes.toHex(r.res()));
        assertEquals("aa689c648370", Bytes.toHex(r.ak()));
        assertEquals("b40ba9a3c58b2a05bbf0d987b21bf8cb", Bytes.toHex(r.ck()));
        assertEquals("f769bcd751044604127672711c6d3441", Bytes.toHex(r.ik()));
    }

    @Test
    void opcFromOp_testSet1() {
        byte[] k = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] op = Bytes.parseHex("cdc202d5123e20f62b6d676ac72cb318");
        assertEquals("cd63cb71954a9f4e48a5994e37a02baf", Bytes.toHex(Milenage.opcFromOp(k, op)));
    }
}
