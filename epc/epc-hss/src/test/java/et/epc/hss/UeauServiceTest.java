package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.crypto.FiveGAka;
import et.epc.crypto.Milenage;
import et.epc.crypto.Sqn;
import et.epc.crypto.UmtsVector;
import et.epc.hss.UeauService.AuthenticationInfoRequest;
import et.epc.hss.UeauService.AuthenticationInfoResult;
import et.epc.hss.UeauService.Av5GHeAka;
import et.epc.hss.UeauService.Outcome;
import et.epc.hss.UeauService.ProblemDetails;
import et.epc.hss.UeauService.ResynchronizationInfo;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Nudm-UEAU generate-auth-data (TS 29.503 §6.3.3.2.4, TS 33.501 §6.1.3.2 / Annex A). */
class UeauServiceTest {

    static final Imsi IMSI = new Imsi("001010000000001");
    static final String SUPI = "imsi-001010000000001";
    static final byte[] KI = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    static final byte[] OPC = Bytes.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    static final byte[] AMF = Bytes.parseHex("8000");
    static final String HOME_SN = "5G:mnc001.mcc001.3gppnetwork.org";
    static final String AUSF = "8e1f2f1c-3a5d-4f4e-9d1a-0b2c3d4e5f60";

    HeapHssRepository repo;
    HeapSecretStore secrets;
    UeauService ueau;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        secrets = new HeapSecretStore();
        secrets.put(IMSI, KI, OPC, AMF);
        repo.save(record(IMSI, true));
        ueau = new UeauService(repo, secrets);
    }

    static SubscriberRecord record(Imsi imsi, boolean enabled) {
        return new SubscriberRecord(new SubscriberProfile(imsi, "1234567890", enabled, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0);
    }

    static AuthenticationInfoRequest req(String sn) {
        return new AuthenticationInfoRequest(sn, null, AUSF);
    }

    AuthenticationInfoResult ok(String supiOrSuci, AuthenticationInfoRequest r) {
        return assertInstanceOf(AuthenticationInfoResult.class, ueau.generateAuthData(supiOrSuci, r));
    }

    static void assertProblem(int status, String cause, Outcome o) {
        ProblemDetails p = assertInstanceOf(ProblemDetails.class, o);
        assertEquals(status, p.status(), p.toString());
        assertEquals(cause, p.cause(), p.toString());
    }

    /** USIM side (TS 33.102 §6.3.3): verify MAC-A, recover SQN from AUTN. */
    static long usimSqn(byte[] rand, byte[] autn) {
        byte[] amf = Bytes.copy(autn, 6, 2);
        Milenage.Result first = Milenage.compute(KI, OPC, rand, new byte[6], amf);
        byte[] sqn = Bytes.xor(Bytes.copy(autn, 0, 6), first.ak());
        Milenage.Result m = Milenage.compute(KI, OPC, rand, sqn, amf);
        assertEquals(Bytes.toHex(m.macA()), Bytes.toHex(Bytes.copy(autn, 8, 8)), "MAC-A");
        return Bytes.bytesToSqn(sqn);
    }

    static long usimSqn(Av5GHeAka av) {
        return usimSqn(Bytes.parseHex(av.rand()), Bytes.parseHex(av.autn()));
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void supiReturns5gHeAkaVectorMatchingFiveGAkaOnTheSameRes() {
        AuthenticationInfoResult r = ok(SUPI, req(HOME_SN));
        assertEquals("5G_AKA", r.authType());
        assertEquals(SUPI, r.supi());
        Av5GHeAka av = r.authenticationVector();
        assertEquals("5G_HE_AKA", av.avType());
        assertEquals(32, av.rand().length());
        assertEquals(32, av.autn().length());
        assertEquals(32, av.xresStar().length());
        assertEquals(64, av.kausf().length());
        for (String hex : List.of(av.rand(), av.autn(), av.xresStar(), av.kausf())) {
            assertTrue(hex.matches("[0-9a-f]+"), "lower-case hex: " + hex);
        }

        // Rebuild the AV on the UE side from RAND + the SQN in AUTN and derive RES*/KAUSF independently.
        byte[] rand = Bytes.parseHex(av.rand());
        long sqn = usimSqn(av);
        assertEquals(Sqn.next(0), sqn, "first fresh SQN");
        UmtsVector ue = UmtsVector.generate(KI, OPC, AMF, sqn, rand);
        assertEquals(Bytes.toHex(ue.autn()), av.autn());
        assertEquals(Bytes.toHex(FiveGAka.xresStar(ue.ck(), ue.ik(), HOME_SN, rand, ue.xres())), av.xresStar());
        assertEquals(Bytes.toHex(FiveGAka.kausf(ue.ck(), ue.ik(), HOME_SN, FiveGAka.sqnXorAk(ue.autn()))),
                av.kausf());
        // KAUSF is bound to the SN name: another PLMN gives another key.
        assertFalse(Bytes.toHex(FiveGAka.kausf(ue, "5G:mnc002.mcc001.3gppnetwork.org")).equals(av.kausf()));
    }

    @Test
    void suciNullSchemeIsDeconcealedToImsiAndSupiReturned() {
        AuthenticationInfoResult r = ok("suci-0-001-01-0000-0-0-0000000001", req(HOME_SN));
        assertEquals(SUPI, r.supi());
        assertEquals(Sqn.next(0), usimSqn(r.authenticationVector()));

        Imsi threeDigitMnc = new Imsi("001001123456789");
        secrets.put(threeDigitMnc, KI, OPC, AMF);
        repo.save(record(threeDigitMnc, true));
        assertEquals("imsi-001001123456789", ok("suci-0-001-001-12-0-0-123456789", req(HOME_SN)).supi());
    }

    @Test
    void sqnIsFreshPerCallAndSharedWithMapHlrAuc() {
        HlrAuthService hlr = new HlrAuthService(repo, secrets);
        long expected = 0;

        expected = Sqn.next(expected);
        assertEquals(expected, usimSqn(ok(SUPI, req(HOME_SN)).authenticationVector()));

        expected = Sqn.next(expected);
        UmtsVector map = hlr.sendAuthenticationInfo(IMSI, 1, 3, null, null).quintuplets().get(0);
        assertEquals(expected, usimSqn(map.rand(), map.autn()), "MAP uses the next SQN of the same space");

        expected = Sqn.next(expected);
        assertEquals(expected, usimSqn(ok(SUPI, req(HOME_SN)).authenticationVector()), "no SQN reuse after MAP");
        assertEquals(expected, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void allowlistedServingPlmnIsAccepted() {
        String hostMno = "5G:mnc002.mcc001.3gppnetwork.org";
        assertProblem(403, UeauService.SN_NOT_AUTHORIZED_CAUSE, ueau.generateAuthData(SUPI, req(hostMno)));
        UeauService withHost = new UeauService(repo, secrets, Set.of(new PlmnId("001", "02")));
        Outcome o = withHost.generateAuthData(SUPI, req(hostMno));
        assertInstanceOf(AuthenticationInfoResult.class, o);
    }

    // ---------------------------------------------------------------- identity errors

    @Test
    void nonNullProtectionSchemeIsNotImplemented() {
        assertProblem(501, "UNSUPPORTED_PROTECTION_SCHEME",
                ueau.generateAuthData("suci-0-001-01-0000-1-7-a1b2c3d4e5f6a7b8c9d0", req(HOME_SN)));
        assertProblem(501, "UNSUPPORTED_PROTECTION_SCHEME",
                ueau.generateAuthData("suci-0-001-01-0000-2-1-a1b2c3d4e5f6", req(HOME_SN)));
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn(), "no SQN consumed");
    }

    @Test
    void nullSchemeWithBadKeyIdOrOutputIsRejected() {
        assertProblem(403, "INVALID_HN_PUBLIC_KEY_IDENTIFIER",
                ueau.generateAuthData("suci-0-001-01-0000-0-3-0000000001", req(HOME_SN)));
        assertProblem(403, "INVALID_SCHEME_OUTPUT",
                ueau.generateAuthData("suci-0-001-01-0000-0-0-00000000ab", req(HOME_SN)));
        assertProblem(403, "INVALID_SCHEME_OUTPUT",
                ueau.generateAuthData("suci-0-001-01-0000-0-0-00000000012", req(HOME_SN)));
    }

    @Test
    void unknownDisabledOrNonImsiUserIsNotFound() {
        assertProblem(404, "USER_NOT_FOUND", ueau.generateAuthData("imsi-001019999999999", req(HOME_SN)));
        assertProblem(404, "USER_NOT_FOUND", ueau.generateAuthData("nai-user@example.org", req(HOME_SN)));
        assertProblem(404, "USER_NOT_FOUND", ueau.generateAuthData("suci-0-001", req(HOME_SN)));
        assertProblem(404, "USER_NOT_FOUND", ueau.generateAuthData(null, req(HOME_SN)));

        Imsi disabled = new Imsi("001010000000002");
        secrets.put(disabled, KI, OPC, AMF);
        repo.save(record(disabled, false));
        assertProblem(404, "USER_NOT_FOUND", ueau.generateAuthData("imsi-001010000000002", req(HOME_SN)));
    }

    // ---------------------------------------------------------------- request / SN errors

    @Test
    void servingNetworkNameMissingMalformedOrForeign() {
        assertProblem(400, "MANDATORY_IE_MISSING", ueau.generateAuthData(SUPI, req(null)));
        assertProblem(400, "MANDATORY_IE_INCORRECT",
                ueau.generateAuthData(SUPI, req("5G:mnc01.mcc001.3gppnetwork.org")));
        assertProblem(400, "MANDATORY_IE_INCORRECT",
                ueau.generateAuthData(SUPI, req("mnc001.mcc001.3gppnetwork.org")));
        // well-formed but not the home PLMN and not allowlisted
        assertProblem(403, UeauService.SN_NOT_AUTHORIZED_CAUSE,
                ueau.generateAuthData(SUPI, req("5G:mnc010.mcc262.3gppnetwork.org")));
        // SNPN form is well-formed (TS 29.571) but not served
        assertProblem(403, UeauService.SN_NOT_AUTHORIZED_CAUSE,
                ueau.generateAuthData(SUPI, req(HOME_SN + ":00000000001")));
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn(), "no SQN consumed on refusal");
    }

    @Test
    void ausfInstanceIdIsMandatoryUuid() {
        assertProblem(400, "MANDATORY_IE_MISSING",
                ueau.generateAuthData(SUPI, new AuthenticationInfoRequest(HOME_SN, null, null)));
        assertProblem(400, "MANDATORY_IE_INCORRECT",
                ueau.generateAuthData(SUPI, new AuthenticationInfoRequest(HOME_SN, null, "ausf-1")));
        assertProblem(400, "MANDATORY_IE_MISSING", ueau.generateAuthData(SUPI, null));
    }

    @Test
    void missingSecretIsSystemFailure() {
        Imsi noKey = new Imsi("001010000000003");
        repo.save(record(noKey, true));
        assertProblem(500, "SYSTEM_FAILURE", ueau.generateAuthData("imsi-001010000000003", req(HOME_SN)));
    }

    // ---------------------------------------------------------------- re-synchronisation

    static byte[] auts(long sqnMs, byte[] rand) {
        Milenage.Result first = Milenage.compute(KI, OPC, rand, new byte[6], new byte[2]);
        byte[] sqn6 = Bytes.sqnToBytes(sqnMs);
        return Bytes.concat(Bytes.xor(sqn6, first.aks()), Milenage.compute(KI, OPC, rand, sqn6, new byte[2]).macS());
    }

    @Test
    void resyncAdoptsSqnMsAndContinuesAfterIt() {
        long sqnMs = Sqn.of(5000, 9);
        byte[] rand = new byte[16];
        rand[0] = 7;
        ResynchronizationInfo info = new ResynchronizationInfo(Bytes.toHex(rand), Bytes.toHex(auts(sqnMs, rand)));
        AuthenticationInfoResult r = ok(SUPI, new AuthenticationInfoRequest(HOME_SN, info, AUSF));
        assertEquals(Sqn.next(sqnMs), usimSqn(r.authenticationVector()));
    }

    @Test
    void resyncWithBadMacSIsRejectedAndSqnUntouched() {
        byte[] rand = new byte[16];
        rand[0] = 7;
        byte[] auts = auts(Sqn.of(5000, 9), rand);
        auts[13] ^= 1;
        ResynchronizationInfo info = new ResynchronizationInfo(Bytes.toHex(rand), Bytes.toHex(auts));
        assertProblem(403, "AUTHENTICATION_REJECTED",
                ueau.generateAuthData(SUPI, new AuthenticationInfoRequest(HOME_SN, info, AUSF)));
        assertEquals(0, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void resyncWithBadLengthsIsBadRequest() {
        String rand = "00".repeat(16);
        String auts = "00".repeat(14);
        for (ResynchronizationInfo bad : List.of(
                new ResynchronizationInfo("00".repeat(15), auts),
                new ResynchronizationInfo(rand, "00".repeat(13)),
                new ResynchronizationInfo(rand, null),
                new ResynchronizationInfo("zz".repeat(16), auts))) {
            assertProblem(400, "OPTIONAL_IE_INCORRECT",
                    ueau.generateAuthData(SUPI, new AuthenticationInfoRequest(HOME_SN, bad, AUSF)));
        }
    }

    // ---------------------------------------------------------------- secrets hygiene

    @Test
    void toStringRedactsXresStarAndKausf() {
        Av5GHeAka av = ok(SUPI, req(HOME_SN)).authenticationVector();
        String s = av.toString();
        assertFalse(s.contains(av.kausf()));
        assertFalse(s.contains(av.xresStar()));
        assertTrue(s.contains("<redacted>"));
    }
}
