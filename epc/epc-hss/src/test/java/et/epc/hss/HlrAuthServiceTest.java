package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.crypto.Milenage;
import et.epc.crypto.Sqn;
import et.epc.crypto.UmtsVector;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MAP SendAuthenticationInfo AuC behaviour (TS 29.002 §8.5.2, TS 33.102). */
class HlrAuthServiceTest {

    static final Imsi IMSI = new Imsi("001010000000001");
    static final byte[] KI = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");

    HeapHssRepository repo;
    HlrAuthService hlr;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, KI, OPC, Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        hlr = new HlrAuthService(repo, secrets);
    }

    /** USIM check of AUTN (TS 33.102 §6.3.3): MAC-A and SQN recovery. */
    static long usimSqn(UmtsVector v) {
        byte[] autn = v.autn();
        Milenage.Result first = Milenage.compute(KI, OPC, v.rand(), new byte[6], Bytes.copy(autn, 6, 2));
        byte[] sqn = Bytes.xor(Bytes.copy(autn, 0, 6), first.ak());
        Milenage.Result m = Milenage.compute(KI, OPC, v.rand(), sqn, Bytes.copy(autn, 6, 2));
        assertEquals(Bytes.toHex(m.macA()), Bytes.toHex(Bytes.copy(autn, 8, 8)), "MAC-A");
        return Bytes.bytesToSqn(sqn);
    }

    @Test
    void mapV3ReturnsFreshQuintupletsWithDistinctAnnexCSqns() {
        HlrAuthService.Answer a = hlr.sendAuthenticationInfo(IMSI, 5, 3, null, null);
        assertNull(a.mapError());
        assertEquals(5, a.quintuplets().size());
        Set<Long> sqns = new HashSet<>();
        long expected = 0;
        for (UmtsVector v : a.quintuplets()) {
            expected = Sqn.next(expected);
            assertEquals(expected, usimSqn(v));
            assertTrue(sqns.add(usimSqn(v)));
        }
    }

    @Test
    void requestedCountIsClampedToFive() {
        assertEquals(5, hlr.sendAuthenticationInfo(IMSI, 9, 3, null, null).quintuplets().size());
        assertEquals(1, hlr.sendAuthenticationInfo(IMSI, 0, 3, null, null).quintuplets().size());
    }

    @Test
    void mapV2ServingNodeGetsGsmTriplets() {
        HlrAuthService.Answer a = hlr.sendAuthenticationInfo(IMSI, 2, 2, null, null);
        assertEquals(2, a.triplets().size());
        assertEquals(4, a.triplets().get(0).sres().length);
        assertEquals(8, a.triplets().get(0).kc().length);
        assertTrue(a.quintuplets().isEmpty());
    }

    @Test
    void unknownImsiIsUnknownSubscriber() {
        assertEquals(HlrAuthService.ERR_UNKNOWN_SUBSCRIBER,
                hlr.sendAuthenticationInfo(new Imsi("001019999999999"), 1, 3, null, null).mapError());
    }

    @Test
    void resyncAdoptsUsimSqnAndRejectsBadMacS() {
        long sqnMs = Sqn.of(5000, 9);
        byte[] rand = new byte[16];
        rand[0] = 7;
        Milenage.Result first = Milenage.compute(KI, OPC, rand, new byte[6], new byte[2]);
        byte[] sqn6 = Bytes.sqnToBytes(sqnMs);
        byte[] auts = Bytes.concat(Bytes.xor(sqn6, first.aks()),
                Milenage.compute(KI, OPC, rand, sqn6, new byte[2]).macS());

        HlrAuthService.Answer a = hlr.sendAuthenticationInfo(IMSI, 1, 3, rand, auts);
        assertNull(a.mapError());
        assertEquals(Sqn.next(sqnMs), usimSqn(a.quintuplets().get(0)), "continues after SQN_MS");

        auts[13] ^= 1;
        assertEquals(HlrAuthService.ERR_UNEXPECTED_DATA_VALUE,
                hlr.sendAuthenticationInfo(IMSI, 1, 3, rand, auts).mapError());
    }
}
