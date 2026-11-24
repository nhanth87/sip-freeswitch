package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.crypto.Milenage;
import et.epc.crypto.Sqn;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * H-R10 (b): AIR honors Number-Of-Requested-Vectors (TS 29.272 §7.3.11), capped at
 * {@link HssService#MAX_VECTORS}; H-R2: a present-but-invalid AUTS answers 4181
 * (TS 29.272 §5.2.3.1.3) instead of throwing.
 */
class HssServiceVectorsTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private HeapHssRepository repo;
    private HeapSecretStore secrets;
    private HssService hss;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        hss = new HssService(repo, secrets, HOME);
    }

    private S6aMessage airWithStructuredNumber(int n) {
        return S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .structured(List.of(DiameterAvp.u32(S6aAvp.NUMBER_OF_REQUESTED_VECTORS, n)))
                .build();
    }

    private static long u32(byte[] v) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(v).getInt());
    }

    @Test
    void airDefaultsToOneVector() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits()).build());
        assertEquals(1, aia.findStructured(S6aAvp.AUTHENTICATION_INFO).children().size());
        assertEquals(Sqn.next(0), repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void airReturnsUpToNVectorsWithDistinctSqnAndRand() {
        S6aMessage aia = hss.handle(airWithStructuredNumber(3));
        assertEquals(S6aAvp.SUCCESS, u32(aia.avp(S6aAvp.RESULT_CODE)));
        List<DiameterAvp> vectors =
                aia.findStructured(S6aAvp.AUTHENTICATION_INFO).children();
        assertEquals(3, vectors.size());
        Set<String> rands = new HashSet<>();
        for (DiameterAvp v : vectors) {
            assertEquals(S6aAvp.E_UTRAN_VECTOR, v.code());
            String rand = Bytes.toHex(child(v, S6aAvp.RAND).asOctets());
            assertEquals(32, rand.length());
            rands.add(rand);
        }
        assertEquals(3, rands.size(), "each vector needs its own RAND (own SQN)");
        // Flat map keeps the first vector for back-compat consumers.
        assertEquals(Bytes.toHex(child(vectors.get(0), S6aAvp.RAND).asOctets()),
                Bytes.toHex(aia.avp(S6aAvp.RAND)));
        assertEquals(Sqn.next(Sqn.next(Sqn.next(0))), repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void airCapsVectorsAtFive() {
        S6aMessage aia = hss.handle(airWithStructuredNumber(9));
        assertEquals(5, aia.findStructured(S6aAvp.AUTHENTICATION_INFO).children().size());
    }

    @Test
    void airClampsZeroToOne() {
        S6aMessage aia = hss.handle(airWithStructuredNumber(0));
        assertEquals(1, aia.findStructured(S6aAvp.AUTHENTICATION_INFO).children().size());
    }

    @Test
    void airHonorsGroupedRequestedEutranAuthInfo() {
        S6aMessage req = S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .structured(List.of(DiameterAvp.grouped3gpp(S6aAvp.REQUESTED_EUTRAN_AUTH_INFO,
                        List.of(DiameterAvp.u32(S6aAvp.NUMBER_OF_REQUESTED_VECTORS,
                                DiameterAvp.VENDOR_3GPP, 2, true)))))
                .build();
        S6aMessage aia = hss.handle(req);
        assertEquals(2, aia.findStructured(S6aAvp.AUTHENTICATION_INFO).children().size());
    }

    @Test
    void airBadAutsAnswers4181WithNoSqnConsumed() {
        byte[] rand = new byte[16];
        byte[] badAuts = new byte[14]; // MAC-S cannot verify
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.RAND, rand)
                .put(S6aAvp.RE_SYNCHRONIZATION_INFO, badAuts)
                .build());
        assertEquals(S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNull(aia.avp(S6aAvp.KASME));
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void airAutsWithoutRandAnswers4181() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.RE_SYNCHRONIZATION_INFO, new byte[14])
                .build());
        assertEquals(S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void airShortAutsAnswers4181() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.RAND, new byte[16])
                .put(S6aAvp.RE_SYNCHRONIZATION_INFO, new byte[5])
                .build());
        assertEquals(S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE, u32(aia.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void airTamperedAutsAnswers4181() {
        // Real Milenage AUTS, then one MAC-S bit flipped (TS 33.102 §6.3.5 check).
        byte[] rand = random16();
        byte[] ki = secrets.ki(IMSI);
        byte[] opc = secrets.opc(IMSI);
        byte[] amfStar = {0x00, 0x00};
        byte[] sqnMs = Bytes.sqnToBytes(1000);
        Milenage.Result aksSource = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        Milenage.Result macSource = Milenage.compute(ki, opc, rand, sqnMs, amfStar);
        byte[] auts = Bytes.concat(Bytes.xor(sqnMs, aksSource.aks()), macSource.macS());
        auts[13] ^= 0x01;
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.RAND, rand)
                .put(S6aAvp.RE_SYNCHRONIZATION_INFO, auts)
                .build());
        assertEquals(S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNotNull(aia.findStructured(S6aAvp.AUTH_SESSION_STATE));
    }

    private static DiameterAvp child(DiameterAvp group, int code) {
        return group.children().stream().filter(a -> a.code() == code).findFirst().orElseThrow();
    }

    private static byte[] random16() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        return b;
    }
}
