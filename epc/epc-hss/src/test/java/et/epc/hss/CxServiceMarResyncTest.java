package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.crypto.Milenage;
import et.epc.crypto.Sqn;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * H-R10 (a): Cx MAR AUTS resync (TS 29.228 §6.3.3, TS 33.203) with real Milenage values;
 * H-R10 (b): MAR honors SIP-Number-Auth-Items (cap {@link CxService#MAX_AUTH_ITEMS}).
 */
class CxServiceMarResyncTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final SecureRandom RNG = new SecureRandom();

    private HeapHssRepository repo;
    private CxService cx;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true,
                new PlmnId("001", "01"), List.of(ApnConfig.internet()), "internet",
                QosProfile.pilotDefault(), 0), 0));
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        cx = new CxService(repo, secrets);
    }

    /** Real Milenage AUTS for SQN_MS (TS 33.102 §6.3.5): (SQN_MS ⊕ AK*) ‖ MAC-S. */
    private static byte[] autsFor(byte[] ki, byte[] opc, byte[] rand, long sqnMs) {
        byte[] amfStar = {0x00, 0x00};
        byte[] sqn6 = Bytes.sqnToBytes(sqnMs);
        Milenage.Result aksSource = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        Milenage.Result macSource = Milenage.compute(ki, opc, rand, sqn6, amfStar);
        return Bytes.concat(Bytes.xor(sqn6, aksSource.aks()), macSource.macS());
    }

    private static byte[] random16() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return b;
    }

    @Test
    void marResyncWithValidAutsAdoptsSqnMsAndReturnsFreshVector() {
        byte[] rand = random16();
        byte[] ki = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] opc = Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1");
        byte[] auts = autsFor(ki, opc, rand, 1000);

        CxService.CxResult r = cx.mar(IMSI.digits(), null, 1, rand, auts);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        assertNotNull(r.avps().get(CxAvp.SIP_AUTHENTICATE));
        // HE adopted SQN_MS=1000 (ahead of 0) and reserved the next SQN for the vector.
        assertEquals(Sqn.next(1000), repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void marResyncWithBadMacSAnswers4181WithNoSqnConsumed() {
        byte[] rand = random16();
        byte[] ki = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] opc = Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1");
        byte[] auts = autsFor(ki, opc, rand, 1000);
        auts[13] ^= 0x01;

        CxService.CxResult r = cx.mar(IMSI.digits(), null, 1, rand, auts);
        assertEquals(CxAvp.AUTHENTICATION_DATA_UNAVAILABLE, r.resultCode());
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void marResyncMissingRandAnswers4181() {
        byte[] auts = new byte[14];
        CxService.CxResult r = cx.mar(IMSI.digits(), null, 1, null, auts);
        assertEquals(CxAvp.AUTHENTICATION_DATA_UNAVAILABLE, r.resultCode());
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void mar4181IsExperimentalResult() {
        var answer = CxService.CxResult.error(CxAvp.AUTHENTICATION_DATA_UNAVAILABLE)
                .toAnswer("sess", 303, 9L, 10L);
        assertEquals(0L, answer.resultCode(), "no Result-Code AVP on a 4181 MAA");
        DiameterAvp exp = answer.avpsStructured().stream()
                .filter(a -> a.code() == CxAvp.EXPERIMENTAL_RESULT).findFirst().orElseThrow();
        assertEquals(10_415L, exp.children().stream().filter(c -> c.code() == CxAvp.VENDOR_ID)
                .findFirst().orElseThrow().asUnsigned32());
        assertEquals(4181L, exp.children().stream()
                .filter(c -> c.code() == CxAvp.EXPERIMENTAL_RESULT_CODE).findFirst()
                .orElseThrow().asUnsigned32());
    }

    @Test
    void marReturnsUpToNItemsWithDistinctRand() {
        CxService.CxResult r = cx.mar(IMSI.digits(), null, 3, null, null);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        assertEquals("3", r.avps().get(CxAvp.SIP_NUMBER_AUTH_ITEMS));
        List<DiameterAvp> items = r.structuredAvps().stream()
                .filter(a -> a.code() == CxAvp.SIP_AUTH_DATA_ITEM).toList();
        assertEquals(3, items.size());
        Set<String> challenges = new HashSet<>();
        for (DiameterAvp item : items) {
            String auth = item.children().stream()
                    .filter(c -> c.code() == CxAvp.SIP_AUTHENTICATE).findFirst()
                    .orElseThrow().asUtf8();
            assertEquals(64, auth.length(), "SIP-Authenticate = RAND(32hex)‖AUTN(32hex)");
            assertTrue(challenges.add(auth.substring(0, 32)), "each item needs its own RAND");
        }
        assertEquals(Sqn.next(Sqn.next(Sqn.next(0))), repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void marCapsItemsAtFive() {
        CxService.CxResult r = cx.mar(IMSI.digits(), null, 9, null, null);
        assertEquals(5, r.structuredAvps().stream()
                .filter(a -> a.code() == CxAvp.SIP_AUTH_DATA_ITEM).toList().size());
        assertEquals("5", r.avps().get(CxAvp.SIP_NUMBER_AUTH_ITEMS));
    }

    @Test
    void marSingleItemKeepsLegacyShape() {
        CxService.CxResult r = cx.mar(IMSI.digits(), null);
        assertEquals("1", r.avps().get(CxAvp.SIP_NUMBER_AUTH_ITEMS));
        assertEquals(1, r.structuredAvps().stream()
                .filter(a -> a.code() == CxAvp.SIP_AUTH_DATA_ITEM).toList().size());
        assertNull(r.structuredAvps().stream()
                .filter(a -> a.code() == CxAvp.SIP_AUTH_DATA_ITEM).findFirst().orElseThrow()
                .children().stream().filter(c -> c.code() == CxAvp.SIP_ITEM_NUMBER)
                .findFirst().orElse(null));
    }
}
