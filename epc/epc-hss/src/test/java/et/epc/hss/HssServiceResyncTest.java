package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

class HssServiceResyncTest {

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

    private static long u32(byte[] v) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(v).getInt());
    }

    @Test
    void aiaAutnCarriesMacA() {
        S6aMessage aia = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId()).build());
        assertEquals(16, aia.avp(S6aAvp.AUTN).length);
        assertEquals(16, aia.avp(S6aAvp.RAND).length);
    }

    @Test
    void resyncWithValidAutsRecoversAndContinues() {
        S6aMessage first = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId()).build());
        byte[] rand = first.avp(S6aAvp.RAND);
        byte[] ki = secrets.ki(IMSI);
        byte[] opc = secrets.opc(IMSI);
        byte[] amfStar = new byte[] {0, 0};
        Milenage.Result aksSource = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        byte[] sqnMs = Bytes.sqnToBytes(5);
        Milenage.Result macSource = Milenage.compute(ki, opc, rand, sqnMs, amfStar);
        byte[] auts = Bytes.concat(Bytes.xor(sqnMs, aksSource.aks()), macSource.macS());

        S6aMessage resync = hss.handle(S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .put(S6aAvp.VISITED_PLMN_ID, HOME.snId())
                .put(S6aAvp.RAND, rand)
                .put(S6aAvp.RE_SYNCHRONIZATION_INFO, auts).build());
        assertEquals(S6aAvp.SUCCESS, u32(resync.avp(S6aAvp.RESULT_CODE)));
        assertNotNull(resync.avp(S6aAvp.KASME));
        // TS 33.102 §6.3.5: HE adopts SQN_MS only when it is ahead; here the HE (first AIR)
        // already is, so the next vector continues from the HE value (Annex C SEQ/IND).
        long expected = et.epc.crypto.Sqn.next(Math.max(et.epc.crypto.Sqn.next(0), 5L));
        assertEquals(expected, repo.find(IMSI).orElseThrow().sqn());
    }
}