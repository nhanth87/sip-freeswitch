package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.PlmnId;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * TS 33.501 Annex A.2/A.4/A.5 and TS 33.220 Annex B.2.
 *
 * <p>No published 5G AKA (KAUSF / XRES*) test vectors were found in the knowledge vault or in
 * {@code docs/3gpp/md/} (TS 33.501 and TS 35.208 are not cached there; TS 33.501 itself has no KAT annex).
 * Correctness is therefore pinned three ways: (1) an independent in-test computation with raw
 * {@link Mac}/{@link MessageDigest} and a hand-built S, (2) self-derived known answers computed outside
 * Java with Python {@code hmac}/{@code hashlib} on TS 35.208 Test Set 1, and (3) the published
 * TS 35.208 Test Set 1 MILENAGE outputs feeding both (already covered by {@link MilenageTs35208Test}).
 */
class FiveGAkaTest {

    // TS 35.208 Test Set 1 (published MILENAGE vectors).
    private static final byte[] K = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    private static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    private static final byte[] RAND = Bytes.parseHex("23553cbe9637a89d218ae64dae47bf35");
    private static final byte[] SQN = Bytes.parseHex("ff9bb4d0b607");
    private static final byte[] AMF = Bytes.parseHex("b9b9");
    private static final byte[] RES = Bytes.parseHex("a54211d5e3ba50bf");
    private static final byte[] CK = Bytes.parseHex("b40ba9a3c58b2a05bbf0d987b21bf8cb");
    private static final byte[] IK = Bytes.parseHex("f769bcd751044604127672711c6d3441");
    private static final byte[] AK = Bytes.parseHex("aa689c648370");
    /** SQN ⊕ AK for Test Set 1. */
    private static final byte[] SQN_XOR_AK = Bytes.parseHex("55f328b43577");

    private static final String SNN = "5G:mnc001.mcc001.3gppnetwork.org";

    // Self-derived known answers (Python hmac/hashlib, TS 35.208 Test Set 1 + SNN above). Not 3GPP-published.
    private static final String KAT_KAUSF = "474698caf02cc715db2ec0726510cfee6caa5bb1a649cb01224f2e23af94de1b";
    private static final String KAT_XRES_STAR = "f236a7417272bfb2d66d4d670733b527";
    private static final String KAT_XRES_STAR_HIGH_HALF = "bd8c31512fc0622dd6d83661a83095fe";
    private static final String KAT_HXRES_STAR = "20a71900b01776bfd773e8c15a825446";

    // ------------------------------------------------------------------ KDF layout (TS 33.220 B.2)

    @Test
    void kdfInputLayout_tinyInput() {
        byte[] s = FiveGAka.kdfInput((byte) 0x6A, new byte[] {0x41}, new byte[] {0x01, 0x02, 0x03});
        // FC | P0=0x41 | L0=0x0001 | P1=010203 | L1=0x0003
        assertEquals("6a410001010203" + "0003", Bytes.toHex(s));
    }

    @Test
    void kdfInputLayout_noParamsAndEmptyParam() {
        assertEquals("6b", Bytes.toHex(FiveGAka.kdfInput((byte) 0x6B)));
        assertEquals("100000", Bytes.toHex(FiveGAka.kdfInput((byte) 0x10, new byte[0])));
    }

    @Test
    void kdfInputLengthIsTwoOctetsBigEndian() {
        byte[] p = new byte[0x0123];
        byte[] s = FiveGAka.kdfInput((byte) 0x6A, p);
        assertEquals(1 + 0x0123 + 2, s.length);
        assertEquals(0x01, s[s.length - 2] & 0xff);
        assertEquals(0x23, s[s.length - 1] & 0xff);
    }

    @Test
    void kdfRejectsOversizeAndNullParams() {
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.kdfInput((byte) 0x6A, new byte[0x10000]));
        assertThrows(NullPointerException.class, () -> FiveGAka.kdfInput((byte) 0x6A, (byte[]) null));
        assertThrows(NullPointerException.class, () -> FiveGAka.kdf(null, (byte) 0x6A, new byte[1]));
    }

    @Test
    void kdfIsHmacSha256OfS() throws Exception {
        byte[] key = Bytes.parseHex("000102030405060708090a0b0c0d0e0f");
        byte[] out = FiveGAka.kdf(key, (byte) 0x6A, new byte[] {0x41});
        byte[] s = Bytes.parseHex("6a410001");
        assertArrayEquals(rawHmac(key, s), out);
        assertEquals(32, out.length);
    }

    @Test
    void kdfMatchesKasmeForFc0x10() {
        // KASME (TS 33.401 A.2) is the same KDF with FC=0x10, P0=SN id, P1=SQN⊕AK.
        PlmnId plmn = new PlmnId("001", "01");
        byte[] viaKdf = FiveGAka.kdf(Bytes.concat(CK, IK), (byte) 0x10, plmn.snId(), SQN_XOR_AK);
        assertArrayEquals(Kasme.derive(CK, IK, plmn, SQN_XOR_AK), viaKdf);
    }

    // ------------------------------------------------------------------ FC values and lengths

    @Test
    void fcValues() {
        assertEquals(0x6A, FiveGAka.FC_KAUSF);
        assertEquals(0x6B, FiveGAka.FC_XRES_STAR);
    }

    @Test
    void outputLengths() {
        assertEquals(32, FiveGAka.kausf(CK, IK, SNN, SQN_XOR_AK).length);
        byte[] xs = FiveGAka.xresStar(CK, IK, SNN, RAND, RES);
        assertEquals(16, xs.length);
        assertEquals(16, FiveGAka.hxresStar(RAND, xs).length);
    }

    @Test
    void deterministic() {
        assertArrayEquals(FiveGAka.kausf(CK, IK, SNN, SQN_XOR_AK), FiveGAka.kausf(CK, IK, SNN, SQN_XOR_AK));
        assertArrayEquals(FiveGAka.xresStar(CK, IK, SNN, RAND, RES), FiveGAka.xresStar(CK, IK, SNN, RAND, RES));
    }

    @Test
    void inputsAreNotModified() {
        byte[] ck = CK.clone();
        byte[] ik = IK.clone();
        byte[] rand = RAND.clone();
        byte[] res = RES.clone();
        byte[] sx = SQN_XOR_AK.clone();
        FiveGAka.kausf(ck, ik, SNN, sx);
        FiveGAka.xresStar(ck, ik, SNN, rand, res);
        assertArrayEquals(CK, ck);
        assertArrayEquals(IK, ik);
        assertArrayEquals(RAND, rand);
        assertArrayEquals(RES, res);
        assertArrayEquals(SQN_XOR_AK, sx);
    }

    @Test
    void outputsDependOnEveryInput() {
        String other = "5G:mnc010.mcc724.3gppnetwork.org";
        String kausf = Bytes.toHex(FiveGAka.kausf(CK, IK, SNN, SQN_XOR_AK));
        assertNotEquals(kausf, Bytes.toHex(FiveGAka.kausf(CK, IK, other, SQN_XOR_AK)));
        assertNotEquals(kausf, Bytes.toHex(FiveGAka.kausf(CK, IK, SNN, Bytes.parseHex("55f328b43578"))));
        assertNotEquals(kausf, Bytes.toHex(FiveGAka.kausf(IK, CK, SNN, SQN_XOR_AK)));

        String xs = Bytes.toHex(FiveGAka.xresStar(CK, IK, SNN, RAND, RES));
        assertNotEquals(xs, Bytes.toHex(FiveGAka.xresStar(CK, IK, other, RAND, RES)));
        byte[] res2 = RES.clone();
        res2[7] ^= 1;
        assertNotEquals(xs, Bytes.toHex(FiveGAka.xresStar(CK, IK, SNN, RAND, res2)));
        byte[] rand2 = RAND.clone();
        rand2[0] ^= 1;
        assertNotEquals(xs, Bytes.toHex(FiveGAka.xresStar(CK, IK, SNN, rand2, RES)));
    }

    @Test
    void rejectsWrongLengths() {
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.kausf(new byte[15], IK, SNN, SQN_XOR_AK));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.kausf(CK, new byte[17], SNN, SQN_XOR_AK));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.kausf(CK, IK, SNN, new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.xresStar(CK, IK, SNN, new byte[8], RES));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.xresStar(CK, IK, SNN, RAND, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.xresStar(CK, IK, SNN, RAND, new byte[17]));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.hxresStar(RAND, new byte[8]));
        assertThrows(NullPointerException.class, () -> FiveGAka.xresStar(CK, IK, SNN, RAND, null));
        // RES of 4 and 16 octets are legal (TS 33.102 §6.3.2: 32..128 bits).
        assertEquals(16, FiveGAka.xresStar(CK, IK, SNN, RAND, new byte[4]).length);
        assertEquals(16, FiveGAka.xresStar(CK, IK, SNN, RAND, new byte[16]).length);
    }

    // ------------------------------------------------------------------ serving network name

    @Test
    void servingNetworkNameValidation() {
        assertTrue(FiveGAka.isValidServingNetworkName(SNN));
        assertTrue(FiveGAka.isValidServingNetworkName("5G:mnc456.mcc123.3gppnetwork.org"));
        assertFalse(FiveGAka.isValidServingNetworkName(null));
        assertFalse(FiveGAka.isValidServingNetworkName(""));
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mnc01.mcc001.3gppnetwork.org"));      // 2-digit MNC
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mnc001.mcc01.3gppnetwork.org"));      // 2-digit MCC
        assertFalse(FiveGAka.isValidServingNetworkName("5g:mnc001.mcc001.3gppnetwork.org"));     // case
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mcc001.mnc001.3gppnetwork.org"));     // order
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mnc001.mcc001.3gppnetwork.org "));    // trailing
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mnc001.mcc001.3gppnetwork.org:abc")); // SNPN NID
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mnc001xmcc001.3gppnetwork.org"));     // dot literal
        assertFalse(FiveGAka.isValidServingNetworkName("5G:mnc٠٠١.mcc001.3gppnetwork.org"));     // non-ASCII digits
        assertFalse(FiveGAka.isValidServingNetworkName("mnc001.mcc001.3gppnetwork.org"));
    }

    @Test
    void derivationsRejectBadServingNetworkName() {
        assertThrows(IllegalArgumentException.class,
                () -> FiveGAka.kausf(CK, IK, "5G:mnc01.mcc001.3gppnetwork.org", SQN_XOR_AK));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.xresStar(CK, IK, null, RAND, RES));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.requireServingNetworkName("epc.mnc001"));
    }

    @Test
    void servingNetworkNameFromPlmnPadsTwoDigitMnc() {
        assertEquals(SNN, FiveGAka.servingNetworkName(new PlmnId("001", "01")));
        assertEquals("5G:mnc010.mcc724.3gppnetwork.org", FiveGAka.servingNetworkName(new PlmnId("724", "10")));
        assertEquals("5G:mnc123.mcc310.3gppnetwork.org", FiveGAka.servingNetworkName(new PlmnId("310", "123")));
    }

    // ------------------------------------------------------------------ independent cross-check

    @Test
    void testSet1_milenageInputsAreThePublishedOnes() {
        Milenage.Result m = Milenage.compute(K, OPC, RAND, SQN, AMF);
        assertArrayEquals(CK, m.ck());
        assertArrayEquals(IK, m.ik());
        assertArrayEquals(RES, m.res());
        assertArrayEquals(AK, m.ak());
        assertArrayEquals(SQN_XOR_AK, Bytes.xor(SQN, AK));
    }

    @Test
    void kausf_matchesIndependentComputation() throws Exception {
        byte[] sn = SNN.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(0x6A);
        s.write(sn);
        s.write(0x00);
        s.write(sn.length); // 32 < 256
        s.write(SQN_XOR_AK);
        s.write(0x00);
        s.write(0x06);
        byte[] key = new byte[32];
        System.arraycopy(CK, 0, key, 0, 16);
        System.arraycopy(IK, 0, key, 16, 16);
        byte[] expected = rawHmac(key, s.toByteArray());

        assertArrayEquals(expected, FiveGAka.kausf(CK, IK, SNN, SQN_XOR_AK));
        assertEquals(KAT_KAUSF, Bytes.toHex(expected));
    }

    @Test
    void xresStar_matchesIndependentComputation_lowest128Bits() throws Exception {
        byte[] sn = SNN.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        s.write(0x6B);
        s.write(sn);
        s.write(0x00);
        s.write(sn.length);
        s.write(RAND);
        s.write(0x00);
        s.write(0x10);
        s.write(RES);
        s.write(0x00);
        s.write(0x08);
        byte[] key = new byte[32];
        System.arraycopy(CK, 0, key, 0, 16);
        System.arraycopy(IK, 0, key, 16, 16);
        byte[] full = rawHmac(key, s.toByteArray());
        byte[] expected = Arrays.copyOfRange(full, 16, 32);

        byte[] actual = FiveGAka.xresStar(CK, IK, SNN, RAND, RES);
        assertArrayEquals(expected, actual);
        assertEquals(KAT_XRES_STAR, Bytes.toHex(actual));
        // Guard against taking the most significant half by mistake.
        assertEquals(KAT_XRES_STAR_HIGH_HALF, Bytes.toHex(Arrays.copyOfRange(full, 0, 16)));
        assertNotEquals(KAT_XRES_STAR_HIGH_HALF, Bytes.toHex(actual));
    }

    @Test
    void hxresStar_matchesIndependentComputation() throws Exception {
        byte[] xs = FiveGAka.xresStar(CK, IK, SNN, RAND, RES);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Bytes.concat(RAND, xs));
        byte[] expected = Arrays.copyOfRange(digest, 16, 32);
        assertArrayEquals(expected, FiveGAka.hxresStar(RAND, xs));
        assertEquals(KAT_HXRES_STAR, Bytes.toHex(expected));
    }

    @Test
    void umtsVectorOverloads_useAutnSqnXorAkAndXres() {
        UmtsVector av = UmtsVector.generate(K, OPC, AMF, Bytes.bytesToSqn(SQN), RAND);
        assertArrayEquals(SQN_XOR_AK, FiveGAka.sqnXorAk(av.autn()));
        assertEquals(KAT_KAUSF, Bytes.toHex(FiveGAka.kausf(av, SNN)));
        assertEquals(KAT_XRES_STAR, Bytes.toHex(FiveGAka.xresStar(av, SNN)));
        assertThrows(IllegalArgumentException.class, () -> FiveGAka.sqnXorAk(new byte[6]));
    }

    private static byte[] rawHmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }
}
