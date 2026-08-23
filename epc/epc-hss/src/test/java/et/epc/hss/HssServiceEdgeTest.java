package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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

class HssServiceEdgeTest {

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
                List.of(ApnConfig.internet(), ApnConfig.ims("192.0.2.10")), "internet",
                QosProfile.pilotDefault(), 0), 0));
        hss = new HssService(repo, secrets, HOME);
    }

    private S6aMessage airWith(String imsi) {
        return S6aMessage.builder(S6aCommand.AIR, true).putUtf8(S6aAvp.USER_NAME, imsi).build();
    }

    private static long u32(byte[] v) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(v).getInt());
    }

    @Test
    void missingUserNameIsMissingAvp() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true).build());
        assertEquals(S6aCommand.AIA, aia.command());
        assertEquals(S6aAvp.MISSING_AVP, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNotNull(aia.findStructured(S6aAvp.AUTH_SESSION_STATE));
    }

    @Test
    void blankUserNameIsMissingAvp() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, "  ").build());
        assertEquals(S6aCommand.AIA, aia.command());
        assertEquals(S6aAvp.MISSING_AVP, u32(aia.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void malformedUserNameIsInvalidAvpValue() {
        S6aMessage aia = hss.handle(airWith("not-an-imsi"));
        assertEquals(S6aCommand.AIA, aia.command());
        assertEquals(S6aAvp.INVALID_AVP_VALUE, u32(aia.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void disabledSubscriberFailsAir() {
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", false, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        S6aMessage aia = hss.handle(airWith(IMSI.digits()));
        assertEquals(S6aAvp.ERROR_USER_UNKNOWN, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNull(aia.avp(S6aAvp.KASME));
    }

    @Test
    void airWithoutVisitedPlmnFallsBackToHome() {
        S6aMessage aia = hss.handle(airWith(IMSI.digits()));
        assertEquals(S6aAvp.SUCCESS, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNotNull(aia.avp(S6aAvp.KASME));
    }

    @Test
    void airWithMalformedVisitedPlmnFallsBackToHome() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, new byte[] {1, 2})
                .build());
        assertEquals(S6aAvp.SUCCESS, u32(aia.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void ulrWithMmeChangeAddsCancellationType() {
        hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.old").build());
        S6aMessage ula = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.new").build());
        assertEquals(S6aAvp.SUCCESS, u32(ula.avp(S6aAvp.RESULT_CODE)));
        assertEquals("MME_UPDATE_PROCEDURE:mme.old", ula.avpUtf8(S6aAvp.CANCELLATION_TYPE));
    }

    @Test
    void ulrWithSameMmeOmitsCancellationType() {
        hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.a").build());
        S6aMessage ula = hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.a").build());
        assertNull(ula.avpUtf8(S6aAvp.CANCELLATION_TYPE));
    }

    @Test
    void ulrClearsPurged() {
        repo.setPurged(IMSI, true);
        assertEquals(true, repo.find(IMSI).orElseThrow().purged());
        hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.a").build());
        assertEquals(false, repo.find(IMSI).orElseThrow().purged());
    }

    @Test
    void clrAndDsrClearServingMme() {
        hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.a").build());
        hss.handle(S6aMessage.builder(S6aCommand.CLR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits()).build());
        assertNull(repo.find(IMSI).orElseThrow().servingMmeHost());
        hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.b").build());
        hss.handle(S6aMessage.builder(S6aCommand.DSR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits()).build());
        assertNull(repo.find(IMSI).orElseThrow().servingMmeHost());
    }
}