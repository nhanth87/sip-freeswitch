package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** TS 35.208 test set 1 through the quintuplet / AUTS / c2-c3 helpers. */
class UmtsVectorTest {

    static final byte[] K = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    static final byte[] RAND = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
    static final byte[] AMF = Bytes.parseHex("b9b9");
    static final long SQN = Bytes.bytesToSqn(Bytes.parseHex("ff9bb4d0b607"));

    @Test
    void autnConcealsSqnWithAk() {
        UmtsVector v = UmtsVector.generate(K, OPC, AMF, SQN, RAND);
        // TS 33.102 §6.3.2: AUTN = (SQN ⊕ AK) ‖ AMF ‖ MAC-A, test set 1 AK = aa689c648370
        byte[] sqnXorAk = Bytes.xor(Bytes.parseHex("ff9bb4d0b607"), Bytes.parseHex("aa689c648370"));
        assertEquals(Bytes.toHex(sqnXorAk) + "b9b9" + "4a9ffac354dfafb3", Bytes.toHex(v.autn()));
        assertEquals("a54211d5e3ba50bf", Bytes.toHex(v.xres()));
        assertEquals("b40ba9a3c58b2a05bbf0d987b21bf8cb", Bytes.toHex(v.ck()));
        assertEquals("f769bcd751044604127672711c6d3441", Bytes.toHex(v.ik()));
    }

    @Test
    void autsRoundTripsAndRejectsBadMac() {
        long sqnMs = Sqn.of(1234, 7);
        Milenage.Result first = Milenage.compute(K, OPC, RAND, new byte[6], new byte[2]);
        byte[] sqn6 = Bytes.sqnToBytes(sqnMs);
        Milenage.Result mac = Milenage.compute(K, OPC, RAND, sqn6, new byte[2]);
        byte[] auts = Bytes.concat(Bytes.xor(sqn6, first.aks()), mac.macS());

        assertEquals(sqnMs, Auts.recoverSqnMs(K, OPC, RAND, auts));
        auts[13] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> Auts.recoverSqnMs(K, OPC, RAND, auts));
    }

    @Test
    void gsmTripletUsesC2AndC3() {
        UmtsVector v = UmtsVector.generate(K, OPC, AMF, SQN, RAND);
        UmtsVector.GsmTriplet t = v.toGsmTriplet();
        byte[] x = v.xres();
        byte[] sres = new byte[4];
        for (int i = 0; i < 8; i++) {
            sres[i % 4] ^= x[i];   // 64-bit XRES: words 3 and 4 are zero padding
        }
        assertArrayEquals(sres, t.sres());
        byte[] kc = new byte[8];
        for (int i = 0; i < 8; i++) {
            kc[i] = (byte) (v.ck()[i] ^ v.ck()[i + 8] ^ v.ik()[i] ^ v.ik()[i + 8]);
        }
        assertArrayEquals(kc, t.kc());
        assertArrayEquals(RAND, t.rand());
    }
}
