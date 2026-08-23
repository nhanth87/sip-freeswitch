package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
 * Verifies the grouped Cx answers CxService now produces for an Open5GS S-CSCF:
 * MAR→MAA SIP-Auth-Data-Item(612) with 3GPP leaves, SAR→SAA
 * Charging-Information(618), and UAA FIRST/SUBSEQUENT registration codes.
 * No Postgres.
 */
class CxStructuredAvpTest {

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

    private static DiameterAvp find(List<DiameterAvp> avps, int code) {
        return avps.stream().filter(a -> a.code() == code).findFirst().orElse(null);
    }

    private static DiameterAvp child(DiameterAvp group, int code) {
        return group.children().stream()
                .filter(a -> a.code() == code).findFirst().orElse(null);
    }

    @Test
    void marGroupsSipAuthDataItemWith3gppLeaves() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService.CxResult r = cx(repo).mar(IMSI.digits(), null);
        assertEquals(CxAvp.SUCCESS, r.resultCode());

        DiameterAvp sadi = find(r.structuredAvps(), CxAvp.SIP_AUTH_DATA_ITEM);
        assertNotNull(sadi, "SIP-Auth-Data-Item(612) must be present");
        assertEquals(CxAvp.SIP_AUTH_DATA_ITEM, sadi.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, sadi.vendorId());
        assertEquals(DiameterAvp.Type.GROUPED, sadi.type());

        DiameterAvp scheme = child(sadi, CxAvp.SIP_AUTHENTICATION_SCHEME);
        assertEquals(CxAvp.SCHEME_AKA_V1_MD5, scheme.asUtf8());
        DiameterAvp authenticate = child(sadi, CxAvp.SIP_AUTHENTICATE);
        assertNotNull(authenticate.asUtf8());
        // SIP-Authenticate = RAND||AUTN = 32 octets of hex.
        assertEquals(32, authenticate.asUtf8().length() / 2);
        assertEquals(32, Bytes.parseHex(authenticate.asUtf8()).length);
        DiameterAvp authorization = child(sadi, CxAvp.SIP_AUTHORIZATION);
        assertNotNull(authorization.asUtf8());
        assertEquals(child(sadi, CxAvp.CONFIDENTIALITY_KEY).asOctets().length, 16);
        assertEquals(child(sadi, CxAvp.INTEGRITY_KEY).asOctets().length, 16);
        // Vendor/M bits on the octet leaves.
        assertEquals(DiameterAvp.VENDOR_3GPP,
                child(sadi, CxAvp.CONFIDENTIALITY_KEY).vendorId());

        DiameterAvp publicId = find(r.structuredAvps(), CxAvp.PUBLIC_IDENTITY);
        assertNotNull(publicId, "Public-Identity(601) top-level");
        assertEquals(IMSI.digits(), publicId.asUtf8());
        DiameterAvp sessionState = find(r.structuredAvps(), CxAvp.AUTH_SESSION_STATE);
        assertNotNull(sessionState, "Auth-Session-State(277)");
        assertEquals(1L, sessionState.asUnsigned32());
    }

    @Test
    void sarGroupsChargingInformation() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService.CxResult r = cx(repo).sar(IMSI.digits(), "sip:user@ims.lab",
                "sip:scscf.ims.lab:5060", CxAvp.SAT_REGISTRATION);
        assertEquals(CxAvp.SUCCESS, r.resultCode());

        DiameterAvp charging = find(r.structuredAvps(), CxAvp.CHARGING_INFORMATION);
        assertNotNull(charging, "Charging-Information(618) must be present");
        assertEquals(CxAvp.CHARGING_INFORMATION, charging.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, charging.vendorId());
        assertEquals(DiameterAvp.Type.GROUPED, charging.type());

        DiameterAvp saaState = find(r.structuredAvps(), CxAvp.AUTH_SESSION_STATE);
        assertNotNull(saaState, "SAA must carry Auth-Session-State(277)");
        assertEquals(1L, saaState.asUnsigned32());

        assertEquals(CxService.PRIMARY_CCF,
                child(charging, CxAvp.PRIMARY_CHARGING_COLLECTION_FUNCTION_NAME).asUtf8());
        assertEquals(CxService.SECONDARY_CCF,
                child(charging, CxAvp.SECONDARY_CHARGING_COLLECTION_FUNCTION_NAME).asUtf8());

        DiameterAvp publicId = find(r.structuredAvps(), CxAvp.PUBLIC_IDENTITY);
        assertNotNull(publicId, "Public-Identity(601) top-level");
        assertEquals("sip:user@ims.lab", publicId.asUtf8());
    }

    @Test
    void uarFirstRegistrationThenSubsequent() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService service = cx(repo);

        // No S-CSCF bound yet -> FIRST_REGISTRATION (2002).
        CxService.CxResult first = service.uar(IMSI.digits(), null);
        assertEquals(CxAvp.FIRST_REGISTRATION, first.resultCode());
        DiameterAvp uaaState = find(first.structuredAvps(), CxAvp.AUTH_SESSION_STATE);
        assertNotNull(uaaState, "UAA must carry Auth-Session-State(277)");
        assertEquals(1L, uaaState.asUnsigned32());
        assertEquals("sip:scscf.ims.lab:5060", first.avps().get(CxAvp.SERVER_NAME));

        // Bind an S-CSCF -> next UAR is a re-registration -> SUBSEQUENT (2003).
        service.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        CxService.CxResult subsequent = service.uar(IMSI.digits(), null);
        assertEquals(CxAvp.SUBSEQUENT_REGISTRATION, subsequent.resultCode());
        assertEquals("sip:scscf.ims.lab:5060", subsequent.avps().get(CxAvp.SERVER_NAME));
    }

    @Test
    void uarUnknownUserIsUserUnknown() {
        CxService.CxResult r = cx(new HeapHssRepository()).uar(IMSI.digits(), null);
        assertEquals(CxAvp.ERROR_USER_UNKNOWN, r.resultCode());
    }

    @Test
    void lirCarriesAuthSessionState() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        repo.saveImsIdentity(IMSI, "impi@001.01", List.of("sip:user@001.01"), "{}");
        repo.setImsScscf(IMSI, "sip:scscf.ims.lab:5060");
        CxService.CxResult r = cx(repo).lir("sip:user@001.01");
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        DiameterAvp state = find(r.structuredAvps(), CxAvp.AUTH_SESSION_STATE);
        assertNotNull(state, "LIA must carry Auth-Session-State(277)");
        assertEquals(1L, state.asUnsigned32());
    }
}