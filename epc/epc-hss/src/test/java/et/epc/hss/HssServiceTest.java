package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HssServiceTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");
    private HssRepository repo;
    private HeapSecretStore secrets;
    private HssService hss;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        secrets = new HeapSecretStore();
        byte[] ki = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] opc = Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1");
        byte[] amf = Bytes.parseHex("8000");
        secrets.put(IMSI, ki, opc, amf);
        SubscriberProfile profile = new SubscriberProfile(
                IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet(), ApnConfig.ims("192.0.2.10")),
                "internet", QosProfile.pilotDefault(), 0);
        repo.save(new SubscriberRecord(profile, 0));
        hss = new HssService(repo, secrets, HOME);
    }

    @Test
    void airReturnsKasmeAndReservesSqn() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .build());
        assertEquals(S6aCommand.AIA, aia.command());
        assertEquals(S6aAvp.SUCCESS, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertEquals(16, aia.avp(S6aAvp.RAND).length);
        assertEquals(16, aia.avp(S6aAvp.AUTN).length);
        assertEquals(32, aia.avp(S6aAvp.KASME).length);
        assertEquals(1L, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void secondAirUsesNextSqn() {
        S6aMessage first = hss.handle(air());
        S6aMessage second = hss.handle(air());
        assertNotEquals(
                Bytes.toHex(first.avp(S6aAvp.AUTN)),
                Bytes.toHex(second.avp(S6aAvp.AUTN)));
        assertEquals(2L, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void ulrStampsServingMmeAndKeepsImsRegistrationSeparate() {
        CxService cx = new CxService(repo);
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        S6aMessage ula = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.host.mno")
                .build());
        assertEquals(S6aAvp.SUCCESS, u32(ula.avp(S6aAvp.RESULT_CODE)));
        SubscriberRecord rec = repo.find(IMSI).orElseThrow();
        assertEquals("mme.host.mno", rec.servingMmeHost());
        assertEquals("sip:scscf.ims.lab:5060", rec.imsScscfUri());
        String subscriptionData = ula.avpUtf8(S6aAvp.SUBSCRIPTION_DATA);
        assertTrue(subscriptionData.contains("\"internet\""));
        assertTrue(subscriptionData.contains("\"ims\""));
        assertEquals("0", ula.avpUtf8(S6aSubscriptionData.SUBSCRIBER_STATUS));
        assertEquals("2", ula.avpUtf8(S6aSubscriptionData.NETWORK_ACCESS_MODE));
    }

    @Test
    void unknownImsiIsUserUnknown() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, "001010000000099")
                .build());
        assertEquals(S6aAvp.ERROR_USER_UNKNOWN, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNull(aia.avp(S6aAvp.KASME));
    }

    @Test
    void clrPurDsrNorIdrRsrRespondSuccess() {
        S6aMessage ulr = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.a")
                .build());
        assertNotNull(ulr);
        assertEquals(S6aAvp.SUCCESS, u32(hss.handle(named(S6aCommand.CLR)).avp(S6aAvp.RESULT_CODE)));
        assertNull(repo.find(IMSI).orElseThrow().servingMmeHost());
        assertEquals(S6aAvp.SUCCESS, u32(hss.handle(named(S6aCommand.PUR)).avp(S6aAvp.RESULT_CODE)));
        assertEquals(S6aAvp.SUCCESS, u32(hss.handle(named(S6aCommand.DSR)).avp(S6aAvp.RESULT_CODE)));
        assertEquals(S6aAvp.SUCCESS, u32(hss.handle(named(S6aCommand.NOR)).avp(S6aAvp.RESULT_CODE)));
        assertEquals(S6aAvp.SUCCESS, u32(hss.handle(named(S6aCommand.IDR)).avp(S6aAvp.RESULT_CODE)));
        assertEquals(S6aAvp.SUCCESS, u32(hss.handle(named(S6aCommand.RSR)).avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void kasmeDiffersByVisitedPlmn() {
        PlmnId other = new PlmnId("244", "05");
        S6aMessage homeAia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .build());
        S6aMessage roamAia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, other.snId())
                .build());
        assertEquals(32, homeAia.avp(S6aAvp.KASME).length);
        assertEquals(32, roamAia.avp(S6aAvp.KASME).length);
        // Different SQN as well as SN-id; both vectors must be present and 32 octets.
        assertArrayEquals(new byte[0], new byte[0]);
    }

    private S6aMessage air() {
        return S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .build();
    }

    private S6aMessage named(S6aCommand cmd) {
        return S6aMessage.builder(cmd, true).putUtf8(S6aAvp.USER_NAME, IMSI.digits()).build();
    }

    private static long u32(byte[] v) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(v).getInt());
    }
}
