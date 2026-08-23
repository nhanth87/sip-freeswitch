package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/**
 * MME peer-trust drawbridge — closes the Open5GS #4337 gap: a ULR/AIR from an origin or
 * visited PLMN outside {@code epc.hss.mme.allowlist} must be refused with
 * DIAMETER_ERROR_UNKNOWN_PEER (3002) and must never touch the serving-MME binding.
 */
class HssServiceSecurityTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");
    private static final PlmnId VISITED = new PlmnId("244", "05");

    private HssRepository repo;
    private HssService hss;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        hss = new HssService(repo, secrets, HOME,
                MmeAllowlist.parse(true, "mme.host.mno,realm:epc.mno,plmn:001-01"),
                "mme.epc.lab");
    }

    @Test
    void ulrFromAllowedOriginHostSucceeds() {
        S6aMessage ula = hss.handle(ulr("mme.host.mno", null, null));
        assertEquals(S6aAvp.SUCCESS, u32(ula.avp(S6aAvp.RESULT_CODE)));
        assertEquals("mme.host.mno", repo.find(IMSI).orElseThrow().servingMmeHost());
    }

    @Test
    void ulrFromUnknownOriginHostIsRefused3002() {
        S6aMessage ula = hss.handle(ulr("rogue.mme.example", null, null));
        assertEquals(S6aAvp.ERROR_UNKNOWN_PEER, u32(ula.avp(S6aAvp.RESULT_CODE)));
        assertNull(repo.find(IMSI).orElseThrow().servingMmeHost());
        assertNull(ula.avpUtf8(S6aAvp.SUBSCRIPTION_DATA));
    }

    @Test
    void ulrFromUnknownPeerNeverTouchesExistingBinding() {
        repo.setServingMme(IMSI, "trusted.mme");
        S6aMessage ula = hss.handle(ulr("rogue.mme.example", null, null));
        assertEquals(S6aAvp.ERROR_UNKNOWN_PEER, u32(ula.avp(S6aAvp.RESULT_CODE)));
        assertEquals("trusted.mme", repo.find(IMSI).orElseThrow().servingMmeHost());
    }

    @Test
    void ulrFromAllowedOriginRealmSucceeds() {
        S6aMessage ula = hss.handle(ulr("any-host.epc.mno", "epc.mno", null));
        assertEquals(S6aAvp.SUCCESS, u32(ula.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void airFromUnknownPeerIsRefused3002() {
        S6aMessage aia = hss.handle(air("rogue.mme.example", null, null));
        assertEquals(S6aCommand.AIA, aia.command());
        assertEquals(S6aAvp.ERROR_UNKNOWN_PEER, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertNull(aia.avp(S6aAvp.KASME));
    }

    @Test
    void airFromAllowedRealmServesVector() {
        S6aMessage aia = hss.handle(air("srv.epc.mno", "epc.mno", null));
        assertEquals(S6aAvp.SUCCESS, u32(aia.avp(S6aAvp.RESULT_CODE)));
        assertEquals(32, aia.avp(S6aAvp.KASME).length);
    }

    @Test
    void visitedPlmnAllowedByPlmnTokenServes() {
        // plmn:001-01 is in the allowlist -> a request visited at the home PLMN is trusted.
        S6aMessage ula = hss.handle(ulr("mme.any", "epc.other", HOME.snId()));
        assertEquals(S6aAvp.SUCCESS, u32(ula.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void visitedPlmnDisallowedByPlmnTokenIsRefused3002() {
        // plmn:244-05 is NOT in the allowlist -> refused even from an allowed-ish host.
        S6aMessage ula = hss.handle(ulr("mme.any", "epc.other", VISITED.snId()));
        assertEquals(S6aAvp.ERROR_UNKNOWN_PEER, u32(ula.avp(S6aAvp.RESULT_CODE)));
        assertNull(repo.find(IMSI).orElseThrow().servingMmeHost());
    }

    @Test
    void enabledButEmptyAllowlistIsFailClosed() {
        HssService guarded = new HssService(repo, new HeapSecretStore(), HOME,
                MmeAllowlist.parse(true, ""), "mme.epc.lab");
        S6aMessage ula = guarded.handle(ulr("mme.host.mno", null, null));
        assertEquals(S6aAvp.ERROR_UNKNOWN_PEER, u32(ula.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void disabledAllowlistTrustsAsBefore() {
        HssService open = new HssService(repo, new HeapSecretStore(), HOME);
        S6aMessage ula = open.handle(ulr("anything.mme", null, null));
        assertEquals(S6aAvp.SUCCESS, u32(ula.avp(S6aAvp.RESULT_CODE)));
    }

    @Test
    void parseSupportsHostRealmAndPlmnTokens() {
        MmeAllowlist l = MmeAllowlist.parse(true, "mme.a,realm:epc.mno,plmn:244-05");
        assertEquals("mme.a", l.originHosts().iterator().next());
        assertTrue(l.originRealms().contains("epc.mno"));
        assertTrue(l.plmns().contains(new PlmnId("244", "05")));
        assertNotNull(l);
    }

    private static S6aMessage ulr(String originHost, String originRealm, byte[] visited) {
        S6aMessage.Builder b = S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits());
        if (originHost != null) {
            b.putUtf8(S6aAvp.ORIGIN_HOST, originHost);
        }
        if (originRealm != null) {
            b.putUtf8(S6aAvp.ORIGIN_REALM, originRealm);
        }
        if (visited != null) {
            b.put(S6aAvp.VISITED_PLMN_ID, visited);
        }
        return b.build();
    }

    private static S6aMessage air(String originHost, String originRealm, byte[] visited) {
        S6aMessage.Builder b = S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits());
        if (originHost != null) {
            b.putUtf8(S6aAvp.ORIGIN_HOST, originHost);
        }
        if (originRealm != null) {
            b.putUtf8(S6aAvp.ORIGIN_REALM, originRealm);
        }
        if (visited != null) {
            b.put(S6aAvp.VISITED_PLMN_ID, visited);
        }
        return b.build();
    }

    private static long u32(byte[] v) {
        return Integer.toUnsignedLong(ByteBuffer.wrap(v).getInt());
    }
}