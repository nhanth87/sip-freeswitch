package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TS 33.203 §6.1.2 review (Phase B): Cx MAR must return IMS AKA vectors carrying the
 * AKAv1-MD5 digest scheme in SIP-Authentication-Scheme(608) — both in the flat AVP map
 * and inside the grouped SIP-Auth-Data-Item(612) consumed by the S-CSCF. Vectors come
 * from Milenage (RES/CK/IK); the EPS vector path (KASME, {@code EpsAka}) is never reused.
 */
class CxServiceMarDigestTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private static SubscriberProfile profile() {
        return new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0);
    }

    private static CxService cx(HeapHssRepository repo) {
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        return new CxService(repo, secrets);
    }

    private static HeapHssRepository repoWithSubscriber() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        return repo;
    }

    private static DiameterAvp find(List<DiameterAvp> avps, int code) {
        return avps.stream().filter(a -> a.code() == code).findFirst().orElse(null);
    }

    private static DiameterAvp child(DiameterAvp group, int code) {
        return group.children().stream()
                .filter(a -> a.code() == code).findFirst().orElse(null);
    }

    @Test
    void marCarriesAkav1Md5DigestSchemeMarker() {
        CxService.CxResult r = cx(repoWithSubscriber()).mar(IMSI.digits(), null);
        assertEquals(CxAvp.SUCCESS, r.resultCode());

        // Flat map: SIP-Authentication-Scheme(608) marks the AKAv1-MD5 digest scheme
        // (RFC 3310 registered name "Digest-AKAv1-MD5", TS 33.203 §6.1.2).
        String flat = r.avps().get(CxAvp.SIP_AUTHENTICATION_SCHEME);
        assertNotNull(flat, "SIP-Authentication-Scheme(608) must be present in the flat map");
        assertEquals(CxAvp.SCHEME_AKA_V1_MD5, flat);
        assertTrue(flat.contains("AKAv1-MD5"), "scheme must mark the AKAv1-MD5 digest algorithm");

        // Grouped SIP-Auth-Data-Item(612): same scheme leaf, 3GPP vendor (Open5GS S-CSCF path).
        DiameterAvp sadi = find(r.structuredAvps(), CxAvp.SIP_AUTH_DATA_ITEM);
        assertNotNull(sadi, "SIP-Auth-Data-Item(612) must be present");
        DiameterAvp scheme = child(sadi, CxAvp.SIP_AUTHENTICATION_SCHEME);
        assertNotNull(scheme, "SIP-Authentication-Scheme(608) inside SIP-Auth-Data-Item(612)");
        assertEquals(DiameterAvp.VENDOR_3GPP, scheme.vendorId());
        assertEquals(CxAvp.SCHEME_AKA_V1_MD5, scheme.asUtf8());
        assertTrue(scheme.asUtf8().contains("AKAv1-MD5"));
    }

    @Test
    void marVectorIsImsAkaNotEps() {
        CxService.CxResult r = cx(repoWithSubscriber()).mar(IMSI.digits(), null);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        assertEquals("1", r.avps().get(CxAvp.SIP_NUMBER_AUTH_ITEMS));
        // IMS AKA shape: challenge RAND||AUTN (32 octets), RES 8, CK 16, IK 16 — no KASME.
        assertEquals(32, Bytes.parseHex(r.avps().get(CxAvp.SIP_AUTHENTICATE)).length);
        assertEquals(8, Bytes.parseHex(r.avps().get(CxAvp.SIP_AUTHORIZATION)).length);
        assertEquals(16, Bytes.parseHex(r.avps().get(CxAvp.CONFIDENTIALITY_KEY)).length);
        assertEquals(16, Bytes.parseHex(r.avps().get(CxAvp.INTEGRITY_KEY)).length);
    }
}
