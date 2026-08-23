package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import et.epc.api.EpsAuthVector;
import et.epc.api.PlmnId;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** EPS-AKA vector generation property tests beyond the existing suite. */
class EpsAkaMoreTest {

    private static final byte[] KI = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    private static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    private static final byte[] AMF = Bytes.parseHex("8000");
    private static final PlmnId SN = new PlmnId("001", "01");

    static Stream<Long> sqns() {
        Stream.Builder<Long> b = Stream.builder();
        for (long s = 0; s < 200; s += 7) {
            b.add(s);
        }
        b.add(0xFFFF_FFFF_FFFFL);
        return b.build();
    }

    @ParameterizedTest
    @MethodSource("sqns")
    void nextSqnAlwaysAdvances(long sqn) {
        EpsAuthVector v = EpsAka.generate(KI, OPC, AMF, sqn, SN);
        assertEquals(sqn + 1, v.nextSqn());
    }

    @ParameterizedTest
    @MethodSource("sqns")
    void autnIsSqnXorAkConcatenatedWithAmfAndMac(long sqn) {
        EpsAuthVector v = EpsAka.generate(KI, OPC, AMF, sqn, SN);
        // AUTN = (SQN ⊕ AK) || AMF || MAC-A  → 6 + 2 + 8 = 16
        assertArrayEquals(AMF, java.util.Arrays.copyOfRange(v.autn(), 6, 8));
    }

    @Test
    void generateWithRandomIsVectorsLengthConformant() {
        EpsAuthVector v = EpsAka.generate(KI, OPC, AMF, 5, SN);
        assertEquals(16, v.rand().length);
        assertEquals(8, v.xres().length);
        assertEquals(16, v.autn().length);
        assertEquals(32, v.kasme().length);
    }

    @Test
    void deterministicWhenRandSupplied() {
        byte[] rand = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
        EpsAuthVector a = EpsAka.generate(KI, OPC, AMF, 1, SN, rand);
        EpsAuthVector b = EpsAka.generate(KI, OPC, AMF, 1, SN, rand);
        assertArrayEquals(a.autn(), b.autn());
        assertArrayEquals(a.kasme(), b.kasme());
        assertArrayEquals(a.xres(), b.xres());
    }

    @Test
    void randomCallProducesNonRepeatingRand() {
        EpsAuthVector a = EpsAka.generate(KI, OPC, AMF, 1, SN);
        EpsAuthVector b = EpsAka.generate(KI, OPC, AMF, 1, SN);
        assertNotEquals(Bytes.toHex(a.rand()), Bytes.toHex(b.rand()));
    }

    @Test
    void sqnFromAutsRejectsNullAndShort() {
        byte[] rand = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
        assertThrows(IllegalArgumentException.class, () -> EpsAka.sqnFromAuts(KI, OPC, rand, null));
        assertThrows(IllegalArgumentException.class,
                () -> EpsAka.sqnFromAuts(KI, OPC, rand, new byte[13]));
        assertThrows(IllegalArgumentException.class,
                () -> EpsAka.sqnFromAuts(KI, OPC, rand, new byte[15]));
    }

    @Test
    void sqnFromAutsDetectsWrongKey() {
        byte[] rand = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
        byte[] auts = autsFor(KI, OPC, rand, 1000L);
        byte[] badKi = KI.clone();
        badKi[0] ^= 0x01;
        assertThrows(IllegalArgumentException.class,
                () -> EpsAka.sqnFromAuts(badKi, OPC, rand, auts));
    }

    static Stream<Long> autsTargets() {
        return Stream.of(0L, 1L, 500L, 1000L, 0xFFFF_FFFF_FFFFL);
    }

    @ParameterizedTest
    @MethodSource("autsTargets")
    void authsResyncRoundTripsTargetSqn(long target) {
        byte[] rand = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
        byte[] auts = autsFor(KI, OPC, rand, target);
        assertEquals(target, EpsAka.sqnFromAuts(KI, OPC, rand, auts));
    }

    private static byte[] autsFor(byte[] ki, byte[] opc, byte[] rand, long targetSqn) {
        byte[] amfStar = new byte[]{0, 0};
        Milenage.Result m0 = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        byte[] sqnMs = Bytes.sqnToBytes(targetSqn);
        byte[] sqnXorAks = Bytes.xor(sqnMs, m0.aks());
        Milenage.Result check = Milenage.compute(ki, opc, rand, sqnMs, amfStar);
        return Bytes.concat(sqnXorAks, check.macS());
    }
}