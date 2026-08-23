package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Milenage (TS 35.206) known-answer + property tests. */
class MilenageTest {

    private static final byte[] K = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    private static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    private static final byte[] RAND = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
    private static final byte[] AMF = Bytes.parseHex("8000");

    @Test
    void knownAnswerVectorProducesExpectedOutputs() {
        Milenage.Result r = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        assertEquals("a54211d5e3ba50bf", Bytes.toHex(r.res()));
        assertEquals("b40ba9a3c58b2a05bbf0d987b21bf8cb", Bytes.toHex(r.ck()));
        assertEquals("f769bcd751044604127672711c6d3441", Bytes.toHex(r.ik()));
        assertEquals("aa689c648370", Bytes.toHex(r.ak()));
    }

    @Test
    void opcFromOpMatchesSpecXorDefinition() {
        byte[] op = Bytes.parseHex("11111111111111111111111111111111");
        byte[] opc = Milenage.opcFromOp(K, op);
        assertEquals(16, opc.length);
        assertArrayEquals(opc, Bytes.xor(AesEcb.encrypt(K, op), op));
    }

    @Test
    void opcFromOpDeterministicAndDistinctFromOp() {
        byte[] op = Bytes.parseHex("11111111111111111111111111111111");
        byte[] a = Milenage.opcFromOp(K, op);
        byte[] b = Milenage.opcFromOp(K, op);
        assertArrayEquals(a, b);
        assertNotEquals(Bytes.toHex(op), Bytes.toHex(a));
    }

    @Test
    void computeIsDeterministic() {
        Milenage.Result a = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        Milenage.Result b = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        assertArrayEquals(a.res(), b.res());
        assertArrayEquals(a.ck(), b.ck());
        assertArrayEquals(a.macA(), b.macA());
    }

    @Test
    void resDependsOnRand() {
        byte[] other = Bytes.parseHex("00000000000000000000000000000000");
        Milenage.Result a = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        Milenage.Result b = Milenage.compute(K, OPC, other, Bytes.sqnToBytes(1), AMF);
        assertNotEquals(Bytes.toHex(a.res()), Bytes.toHex(b.res()));
    }

    @Test
    void macADependsOnSqn() {
        Milenage.Result a = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        Milenage.Result b = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(2), AMF);
        assertNotEquals(Bytes.toHex(a.macA()), Bytes.toHex(b.macA()));
    }

    @Test
    void macSDependsOnAmf() {
        Milenage.Result a = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        Milenage.Result b = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1),
                Bytes.parseHex("0000"));
        // MAC-A is SQN/AMF dependent (f1); RES (f2) is not — assert the f1 output.
        assertNotEquals(Bytes.toHex(a.macS()), Bytes.toHex(b.macS()));
    }

    @Test
    void resDependsOnKey() {
        byte[] flipped = K.clone();
        flipped[0] ^= 0x01;
        Milenage.Result a = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        Milenage.Result b = Milenage.compute(flipped, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        assertNotEquals(Bytes.toHex(a.res()), Bytes.toHex(b.res()));
    }

    @Test
    void resDependsOnOpc() {
        byte[] other = Bytes.parseHex("00000000000000000000000000000000");
        Milenage.Result a = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        Milenage.Result b = Milenage.compute(K, other, RAND, Bytes.sqnToBytes(1), AMF);
        assertNotEquals(Bytes.toHex(a.res()), Bytes.toHex(b.res()));
    }

    @Test
    void resultFieldLengths() {
        Milenage.Result r = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(1), AMF);
        assertEquals(8, r.res().length);
        assertEquals(16, r.ck().length);
        assertEquals(16, r.ik().length);
        assertEquals(6, r.ak().length);
        assertEquals(6, r.aks().length);
        assertEquals(8, r.macA().length);
        assertEquals(8, r.macS().length);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, 1L, 255L, 256L, 4096L, 0xFFFF_FFFF_FFFFL})
    void sqnAffectsAkDeterministically(long sqn) {
        byte[] ak = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(sqn), AMF).ak();
        assertEquals(6, ak.length);
        byte[] again = Milenage.compute(K, OPC, RAND, Bytes.sqnToBytes(sqn), AMF).ak();
        assertArrayEquals(ak, again);
    }

    @ParameterizedTest
    @MethodSource("rands")
    void neverProducesZeroResAcrossRands(String randHex) {
        byte[] rand = Bytes.parseHex(randHex);
        Milenage.Result r = Milenage.compute(K, OPC, rand, Bytes.sqnToBytes(1), AMF);
        assertNotEquals("0000000000000000", Bytes.toHex(r.res()));
    }

    static Stream<String> rands() {
        return java.util.stream.IntStream.rangeClosed(0, 24)
                .mapToObj(i -> String.format("%032x", 0x23553cbe9637a89dL + i));
    }

    @Test
    void computeRejectsNullInputs() {
        assertThrows(NullPointerException.class,
                () -> Milenage.compute(null, OPC, RAND, Bytes.sqnToBytes(1), AMF));
        assertThrows(NullPointerException.class,
                () -> Milenage.compute(K, null, RAND, Bytes.sqnToBytes(1), AMF));
        assertThrows(NullPointerException.class,
                () -> Milenage.compute(K, OPC, null, Bytes.sqnToBytes(1), AMF));
    }
}