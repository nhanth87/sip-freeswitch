package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * H-R11: when the MME allowlist is enabled, PUR from a peer outside the allowlist is
 * refused 3002 with no state change — same drawbridge as AIR/ULR.
 */
class HssServicePurAllowlistTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private HeapHssRepository repo;
    private HssService guarded;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        guarded = new HssService(repo, secrets, HOME,
                MmeAllowlist.parse(true, "mme.host.mno,realm:epc.mno,plmn:001-01"), "mme.epc.lab");
    }

    private static S6aMessage pur(String originHost) {
        S6aMessage.Builder b = S6aMessage.builder(S6aCommand.PUR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits());
        if (originHost != null) {
            b.putUtf8(S6aAvp.ORIGIN_HOST, originHost);
        }
        return b.build();
    }

    private static long u32(byte[] v) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(v).getInt());
    }

    @Test
    void purFromUnknownPeerIsRefused3002WithNoStateChange() {
        S6aMessage pua = guarded.handle(pur("rogue.mme.example"));
        assertEquals(S6aCommand.PUA, pua.command());
        assertEquals(S6aAvp.ERROR_UNKNOWN_PEER, u32(pua.avp(S6aAvp.RESULT_CODE)));
        assertFalse(repo.find(IMSI).orElseThrow().purged());
    }

    @Test
    void purFromAllowedPeerMarksPurged() {
        S6aMessage pua = guarded.handle(pur("mme.host.mno"));
        assertEquals(S6aAvp.SUCCESS, u32(pua.avp(S6aAvp.RESULT_CODE)));
        assertTrue(repo.find(IMSI).orElseThrow().purged());
    }

    @Test
    void purFromAllowedRealmMarksPurged() {
        S6aMessage pua = guarded.handle(S6aMessage.builder(S6aCommand.PUR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "any-host.epc.mno")
                .putUtf8(S6aAvp.ORIGIN_REALM, "epc.mno")
                .build());
        assertEquals(S6aAvp.SUCCESS, u32(pua.avp(S6aAvp.RESULT_CODE)));
        assertTrue(repo.find(IMSI).orElseThrow().purged());
    }

    @Test
    void purWhenAllowlistDisabledServes() {
        HssService open = new HssService(repo, new HeapSecretStore(), HOME);
        S6aMessage pua = open.handle(pur("anything.mme"));
        assertEquals(S6aAvp.SUCCESS, u32(pua.avp(S6aAvp.RESULT_CODE)));
        assertTrue(repo.find(IMSI).orElseThrow().purged());
    }
}
