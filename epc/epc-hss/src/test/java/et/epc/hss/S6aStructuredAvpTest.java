package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.crypto.Milenage;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class S6aStructuredAvpTest {

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

    private S6aMessage air() {
        return S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .build();
    }

    @Test
    void aiaGroupsEUtranVectorUnderAuthenticationInfo() {
        S6aMessage aia = hss.handle(air());
        assertEquals(S6aCommand.AIA, aia.command());
        DiameterAvp authInfo = aia.findStructured(S6aAvp.AUTHENTICATION_INFO);
        assertNotNull(authInfo, "Authentication-Info(1490) must be present");
        assertEquals(S6aAvp.AUTHENTICATION_INFO, authInfo.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, authInfo.vendorId());
        assertEquals(DiameterAvp.Type.GROUPED, authInfo.type());
        assertEquals(1, authInfo.children().size());
        DiameterAvp vector = authInfo.children().get(0);
        assertEquals(S6aAvp.E_UTRAN_VECTOR, vector.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, vector.vendorId());
        assertEquals(DiameterAvp.Type.GROUPED, vector.type());
        assertEquals(4, vector.children().size());
        DiameterAvp rand = child(vector, S6aAvp.RAND);
        DiameterAvp xres = child(vector, S6aAvp.XRES);
        DiameterAvp autn = child(vector, S6aAvp.AUTN);
        DiameterAvp kasme = child(vector, S6aAvp.KASME);
        assertArrayEquals(aia.avp(S6aAvp.RAND), rand.asOctets());
        assertArrayEquals(aia.avp(S6aAvp.XRES), xres.asOctets());
        assertArrayEquals(aia.avp(S6aAvp.AUTN), autn.asOctets());
        assertEquals(16, autn.asOctets().length);
        assertArrayEquals(aia.avp(S6aAvp.KASME), kasme.asOctets());
        assertEquals(32, kasme.asOctets().length);
    }

    @Test
    void ulaGroupsSubscriptionDataWithMsisdnAndAmbr() {
        S6aMessage ula = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.host.mno")
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .build());
        assertEquals(S6aCommand.ULA, ula.command());
        DiameterAvp sub = ula.findStructured(S6aAvp.SUBSCRIPTION_DATA);
        assertNotNull(sub, "Subscription-Data(1400) must be present");
        assertEquals(S6aAvp.SUBSCRIPTION_DATA, sub.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, sub.vendorId());
        assertEquals(DiameterAvp.Type.GROUPED, sub.type());
        DiameterAvp msisdn = child(sub, S6aAvp.MSISDN);
        assertNotNull(msisdn);
        assertArrayEquals(new byte[] {0x21, 0x43, 0x65, (byte) 0x87, 0x09}, msisdn.asOctets());
        DiameterAvp ambr = child(sub, S6aAvp.AMBR);
        assertNotNull(ambr, "AMBR(1435) group inside Subscription-Data");
        assertEquals(S6aAvp.AMBR, ambr.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, ambr.vendorId());
        QosProfile q = QosProfile.pilotDefault();
        assertEquals(q.ueAmbrUlBps(),
                child(ambr, S6aAvp.MAX_REQUESTED_BANDWIDTH_UL).asUnsigned32());
        assertEquals(q.ueAmbrDlBps(),
                child(ambr, S6aAvp.MAX_REQUESTED_BANDWIDTH_DL).asUnsigned32());
        DiameterAvp flags = ula.findStructured(S6aAvp.ULA_FLAGS);
        assertNotNull(flags, "ULA-Flags(1406) top-level");
        assertEquals(S6aAvp.ULA_FLAGS, flags.code());
        assertEquals(DiameterAvp.VENDOR_3GPP, flags.vendorId());
        DiameterAvp visited = ula.findStructured(S6aAvp.VISITED_PLMN_ID);
        assertNotNull(visited, "Visited-PLMN-Id(1407) top-level");
        assertEquals(3, visited.asOctets().length);
        assertArrayEquals(HOME.snId(), visited.asOctets());
    }

    @Test
    void ulaGroupsFullSubscriptionDataWithApnProfileAndQos() {
        S6aMessage ula = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.host.mno")
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .build());
        assertEquals(S6aCommand.ULA, ula.command());

        DiameterAvp sub = ula.findStructured(S6aAvp.SUBSCRIPTION_DATA);
        assertNotNull(sub);
        assertEquals(DiameterAvp.Type.GROUPED, sub.type());

        // Status / network mode / barring leaves.
        assertEquals(0L, child(sub, S6aAvp.SUBSCRIBER_STATUS).asUnsigned32());
        assertEquals(2L, child(sub, S6aAvp.NETWORK_ACCESS_MODE).asUnsigned32());
        assertEquals(0L, child(sub, S6aAvp.OPERATOR_DETERMINED_BARRING).asUnsigned32());
        assertEquals(0L, child(sub, S6aAvp.ACCESS_RESTRICTION_DATA).asUnsigned32());
        assertEquals(720L, child(sub, S6aAvp.SUBSCRIBED_PERIODIC_RAU_TAU_TIMER).asUnsigned32());

        // APN-Configuration-Profile(1429){Context-Identifier=1, APN-Configuration(1430)*}.
        DiameterAvp profile = child(sub, S6aAvp.APN_CONFIGURATION_PROFILE);
        assertNotNull(profile);
        assertEquals(DiameterAvp.Type.GROUPED, profile.type());
        assertEquals(1L, child(profile, S6aAvp.CONTEXT_IDENTIFIER).asUnsigned32());
        assertEquals(0L,
                child(profile, S6aAvp.ALL_APN_CONFIGURATIONS_INCLUDED_INDICATOR).asUnsigned32());

        DiameterAvp apn = child(profile, S6aAvp.APN_CONFIGURATION);
        assertNotNull(apn);
        assertEquals(1L, child(apn, S6aAvp.CONTEXT_IDENTIFIER).asUnsigned32());
        assertEquals(2L, child(apn, S6aAvp.PDN_TYPE).asUnsigned32());   // IPv4v6
        assertEquals("internet", child(apn, S6aAvp.SERVICE_SELECTION).asUtf8());

        // EPS-Subscribed-QoS-Profile(1431){QCI, ARP{priority-level, pre-emption-*}}.
        DiameterAvp qos = child(apn, S6aAvp.EPS_SUBSCRIBED_QOS_PROFILE);
        assertNotNull(qos);
        assertEquals(9L, child(qos, S6aAvp.QOS_CLASS_IDENTIFIER).asUnsigned32());
        DiameterAvp arp = child(qos, S6aAvp.ALLOCATION_RETENTION_PRIORITY);
        assertNotNull(arp);
        assertEquals(8L, child(arp, S6aAvp.PRIORITY_LEVEL).asUnsigned32());
        assertEquals(0L, child(arp, S6aAvp.PRE_EMPTION_CAPABILITY).asUnsigned32());
        assertEquals(1L, child(arp, S6aAvp.PRE_EMPTION_VULNERABILITY).asUnsigned32());

        // APN-AMBR(1435) inside the APN-Configuration.
        DiameterAvp apnAmbr = child(apn, S6aAvp.AMBR);
        assertNotNull(apnAmbr);
        assertEquals(1_000_000_000L,
                child(apnAmbr, S6aAvp.MAX_REQUESTED_BANDWIDTH_UL).asUnsigned32());
        assertEquals(1_000_000_000L,
                child(apnAmbr, S6aAvp.MAX_REQUESTED_BANDWIDTH_DL).asUnsigned32());
    }

    @Test
    void s6aAnswersCarryAuthSessionStateNoStateMaintained() {
        S6aMessage aia = hss.handle(air());
        DiameterAvp aiaState = aia.findStructured(S6aAvp.AUTH_SESSION_STATE);
        assertNotNull(aiaState, "AIA must carry Auth-Session-State(277)");
        assertEquals(1L, aiaState.asUnsigned32());

        S6aMessage ula = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.host.mno")
                .build());
        DiameterAvp ulaState = ula.findStructured(S6aAvp.AUTH_SESSION_STATE);
        assertNotNull(ulaState, "ULA must carry Auth-Session-State(277)");
        assertEquals(1L, ulaState.asUnsigned32());
    }

    @Test
    void sqnResyncReadsRandAndAutsFromStructuredAvps() {
        S6aMessage first = hss.handle(air());
        byte[] rand = first.avp(S6aAvp.RAND);
        byte[] ki = secrets.ki(IMSI);
        byte[] opc = secrets.opc(IMSI);
        byte[] amfStar = new byte[] {0, 0};
        Milenage.Result aksSource = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        byte[] sqnMs = Bytes.sqnToBytes(5);
        Milenage.Result macSource = Milenage.compute(ki, opc, rand, sqnMs, amfStar);
        byte[] auts = Bytes.concat(Bytes.xor(sqnMs, aksSource.aks()), macSource.macS());
        S6aMessage resyncReq = S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .structured(List.of(
                        DiameterAvp.octets(S6aAvp.RAND, DiameterAvp.VENDOR_3GPP, rand, true),
                        DiameterAvp.octets(S6aAvp.RE_SYNCHRONIZATION_INFO,
                                DiameterAvp.VENDOR_3GPP, auts, true)))
                .build();
        S6aMessage aia = hss.handle(resyncReq);
        assertEquals(S6aAvp.SUCCESS,
                Integer.toUnsignedLong(ByteBuffer.wrap(aia.avp(S6aAvp.RESULT_CODE)).getInt()));
        assertNotNull(aia.avp(S6aAvp.KASME));
        // TS 33.102 §6.3.5: HE adopts SQN_MS only when it is ahead; here the HE (first AIR)
        // already is, so the next vector continues from the HE value (Annex C SEQ/IND).
        long expected = et.epc.crypto.Sqn.next(Math.max(et.epc.crypto.Sqn.next(0), 5L));
        assertEquals(expected, repo.find(IMSI).orElseThrow().sqn());
    }

    private static DiameterAvp child(DiameterAvp group, int code) {
        return group.children().stream()
                .filter(a -> a.code() == code)
                .findFirst().orElse(null);
    }
}
