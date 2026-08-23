package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import et.epc.api.PlmnId;
import org.junit.jupiter.api.Test;

class EpsAkaPropertiesTest {

    private static final byte[] KI = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    private static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    private static final byte[] AMF = Bytes.parseHex("8000");
    private static final byte[] RAND = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
    private static final PlmnId SN = new PlmnId("001", "01");

    @Test
    void generateReturnsVectorLengths() {
        var v = EpsAka.generate(KI, OPC, AMF, 1, SN, RAND);
        assertArrayEquals(RAND, v.rand());
        assertEquals(8, v.xres().length);
        assertEquals(16, v.autn().length);
        assertEquals(32, v.kasme().length);
        assertEquals(2, v.nextSqn());
    }

    @Test
    void autnEmbedsAmfBytes() {
        var v = EpsAka.generate(KI, OPC, AMF, 1, SN, RAND);
        assertEquals(AMF[0], v.autn()[6]);
        assertEquals(AMF[1], v.autn()[7]);
    }

    @Test
    void deterministicForSameRand() {
        var a = EpsAka.generate(KI, OPC, AMF, 1, SN, RAND);
        var b = EpsAka.generate(KI, OPC, AMF, 1, SN, RAND);
        assertArrayEquals(a.autn(), b.autn());
        assertArrayEquals(a.kasme(), b.kasme());
    }

    @Test
    void randChangesKasmeAndAutn() {
        var a = EpsAka.generate(KI, OPC, AMF, 1, SN, RAND);
        var b = EpsAka.generate(KI, OPC, AMF, 1, SN, Bytes.parseHex("00000000000000000000000000000000"));
        assertNotEquals(Bytes.toHex(a.kasme()), Bytes.toHex(b.kasme()));
        assertNotEquals(Bytes.toHex(a.autn()), Bytes.toHex(b.autn()));
    }

    @Test
    void sqnFromAutsRoundTrip() {
        long target = 1000L;
        byte[] auts = autsFor(KI, OPC, RAND, target);
        assertEquals(target, EpsAka.sqnFromAuts(KI, OPC, RAND, auts));
    }

    @Test
    void sqnFromAutsRejectsTamperedMacS() {
        byte[] auts = autsFor(KI, OPC, RAND, 1000L);
        auts[13] ^= 0x01;
        assertThrows(IllegalArgumentException.class, () -> EpsAka.sqnFromAuts(KI, OPC, RAND, auts));
    }

    @Test
    void sqnFromAutsRejectsWrongLength() {
        assertThrows(IllegalArgumentException.class,
                () -> EpsAka.sqnFromAuts(KI, OPC, RAND, new byte[13]));
        assertThrows(IllegalArgumentException.class,
                () -> EpsAka.sqnFromAuts(KI, OPC, RAND, null));
    }

    private static byte[] autsFor(byte[] ki, byte[] opc, byte[] rand, long targetSqn) {
        byte[] amfStar = new byte[] {0, 0};
        Milenage.Result m0 = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        byte[] sqnMs = Bytes.sqnToBytes(targetSqn);
        byte[] sqnXorAks = Bytes.xor(sqnMs, m0.aks());
        Milenage.Result check = Milenage.compute(ki, opc, rand, sqnMs, amfStar);
        return Bytes.concat(sqnXorAks, check.macS());
    }
}