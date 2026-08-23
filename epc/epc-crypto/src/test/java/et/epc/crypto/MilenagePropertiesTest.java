package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MilenagePropertiesTest {

    private static final byte[] K = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    private static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    private static final byte[] RAND = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
    private static final byte[] SQN = Bytes.parseHex("ff9bb4d0b607");
    private static final byte[] AMF = Bytes.parseHex("b9b9");

    @Test
    void computeIsDeterministic() {
        Milenage.Result a = Milenage.compute(K, OPC, RAND, SQN, AMF);
        Milenage.Result b = Milenage.compute(K, OPC, RAND, SQN, AMF);
        assertArrayEquals(a.res(), b.res());
        assertArrayEquals(a.ck(), b.ck());
        assertArrayEquals(a.ik(), b.ik());
    }

    @Test
    void resultFieldLengths() {
        Milenage.Result r = Milenage.compute(K, OPC, RAND, SQN, AMF);
        assertEquals(8, r.macA().length);
        assertEquals(8, r.macS().length);
        assertEquals(8, r.res().length);
        assertEquals(16, r.ck().length);
        assertEquals(16, r.ik().length);
        assertEquals(6, r.ak().length);
        assertEquals(6, r.aks().length);
    }

    @Test
    void sqnChangesMacAButNotF2F3F4F5() {
        Milenage.Result a = Milenage.compute(K, OPC, RAND, SQN, AMF);
        Milenage.Result b = Milenage.compute(K, OPC, RAND, Bytes.parseHex("ff9bb4d0b608"), AMF);
        // f1 depends on SQN: MAC-A changes when SQN changes.
        assertNotEquals(Bytes.toHex(a.macA()), Bytes.toHex(b.macA()));
        assertNotEquals(Bytes.toHex(a.macS()), Bytes.toHex(b.macS()));
        // res/ck/ik/ak/aks (f2/f3/f4/f5/f5*) are independent of SQN.
        assertArrayEquals(a.res(), b.res());
        assertArrayEquals(a.ck(), b.ck());
        assertArrayEquals(a.ik(), b.ik());
        assertArrayEquals(a.ak(), b.ak());
        assertArrayEquals(a.aks(), b.aks());
    }

    @Test
    void randDrivesAllFields() {
        Milenage.Result a = Milenage.compute(K, OPC, RAND, SQN, AMF);
        Milenage.Result b = Milenage.compute(K, OPC, Bytes.parseHex("00000000000000000000000000000000"), SQN, AMF);
        assertNotEquals(Bytes.toHex(a.macA()), Bytes.toHex(b.macA()));
        assertNotEquals(Bytes.toHex(a.ck()), Bytes.toHex(b.ck()));
    }

    @Test
    void macADiffersFromRes() {
        Milenage.Result r = Milenage.compute(K, OPC, RAND, SQN, AMF);
        assertNotEquals(Bytes.toHex(r.macA()), Bytes.toHex(r.res()));
    }

    @Test
    void nullInputsRejected() {
        assertThrows(NullPointerException.class, () -> Milenage.compute(null, OPC, RAND, SQN, AMF));
        assertThrows(NullPointerException.class, () -> Milenage.compute(K, null, RAND, SQN, AMF));
        assertThrows(NullPointerException.class, () -> Milenage.compute(K, OPC, null, SQN, AMF));
    }
}