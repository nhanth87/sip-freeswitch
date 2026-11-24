package et.epc.hss.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.hss.CxAvp;
import et.epc.hss.CxService;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HssRepository;
import et.epc.hss.HssService;
import et.epc.hss.S6aAvp;
import et.epc.hss.SubscriberRecord;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * H-R2: a throwing repository, a bad AUTS / missing RAND on S6a AIR, or an unknown S6a
 * command still emits exactly one answer (5012, or 4181 for AUTS) with the request's
 * hop-by-hop / end-to-end ids.
 */
class HssSbbCatchAllTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort recordingPort = sent::add;

    private HeapHssRepository heap;
    private HssSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        heap = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        heap.save(new SubscriberRecord(
                new SubscriberProfile(IMSI, "1234567890", true, HOME,
                        List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0),
                0));
        sbb = new HssSbb.$Concrete(new HssService(heap, secrets, HOME),
                new CxService(heap, secrets), null);
        injectPort(sbb);
    }

    private void injectPort(HssSbb target) throws Exception {
        Field f = HssSbb.class.getDeclaredField("diameterRa");
        f.setAccessible(true);
        f.set(target, recordingPort);
    }

    /** Repository that throws on vector reservation (JDBC failure oracle). */
    private static HssRepository failingOnReserve(HssRepository delegate) {
        return (HssRepository) Proxy.newProxyInstance(HssSbbCatchAllTest.class.getClassLoader(),
                new Class<?>[] {HssRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("reserveSqn")) {
                        throw new RuntimeException("db down");
                    }
                    return method.invoke(delegate, args);
                });
    }

    private static DiameterRequestEvent req(long appId, int cc, Map<Integer, String> avps) {
        return new DiameterRequestEvent("sess-9", appId, cc, 0xAABBL, 0xCCDDL,
                "mme.host", "epc.mno", "hss.epc", "epc.lab", avps);
    }

    private static DiameterRequestEvent reqStructured(long appId, int cc,
                                                      Map<Integer, String> avps,
                                                      List<DiameterAvp> structured) {
        return new DiameterRequestEvent("sess-9", appId, cc, 0xAABBL, 0xCCDDL,
                "mme.host", "epc.mno", "hss.epc", "epc.lab", avps, structured);
    }

    private SendDiameterAnswer last() {
        assertFalse(sent.isEmpty(), "expected exactly one answer");
        assertEquals(1, sent.size());
        return (SendDiameterAnswer) sent.get(0);
    }

    private static HssSbb.$Concrete withRepo(HssSbbCatchAllTest t, HssRepository repo) throws Exception {
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        HssSbb.$Concrete failing = new HssSbb.$Concrete(
                new HssService(repo, secrets, HOME), new CxService(repo, secrets), null);
        t.injectPort(failing);
        return failing;
    }

    @Test
    void throwingRepositoryOnAirAnswers5012WithSameIds() throws Exception {
        HssSbb.$Concrete failing = withRepo(this, failingOnReserve(heap));
        failing.onEvent(req(HssSbb.S6A, 318, Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                S6aAvp.VISITED_PLMN_ID, Bytes.toHex(HOME.snId()))), null);
        SendDiameterAnswer ans = (SendDiameterAnswer) sent.get(sent.size() - 1);
        assertEquals(5012L, ans.resultCode());
        assertEquals(0xAABBL, ans.hopByHopId());
        assertEquals(0xCCDDL, ans.endToEndId());
        assertEquals(HssSbb.S6A, ans.applicationId());
    }

    @Test
    void badAutsOnAirAnswers4181WithSameIds() {
        sbb.onEvent(req(HssSbb.S6A, 318, Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                S6aAvp.RAND, HexFormat.of().formatHex(new byte[16]),
                S6aAvp.RE_SYNCHRONIZATION_INFO, HexFormat.of().formatHex(new byte[14]))), null);
        SendDiameterAnswer ans = last();
        assertEquals(4181L, ans.resultCode());
        assertEquals(0xAABBL, ans.hopByHopId());
        assertEquals(0xCCDDL, ans.endToEndId());
    }

    @Test
    void autsWithoutRandOnAirAnswers4181() {
        sbb.onEvent(req(HssSbb.S6A, 318, Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                S6aAvp.RE_SYNCHRONIZATION_INFO, HexFormat.of().formatHex(new byte[14]))), null);
        assertEquals(4181L, last().resultCode());
    }

    @Test
    void throwingRepositoryOnCxMarAnswers5012() throws Exception {
        HssSbb.$Concrete failing = withRepo(this, failingOnReserve(heap));
        sent.clear();
        failing.onEvent(req(HssSbb.CX, 303, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        SendDiameterAnswer ans = (SendDiameterAnswer) sent.get(sent.size() - 1);
        assertEquals(5012L, ans.resultCode());
        assertEquals(HssSbb.CX, ans.applicationId());
        assertEquals(0xAABBL, ans.hopByHopId());
        assertEquals(0xCCDDL, ans.endToEndId());
    }

    @Test
    void unknownS6aCommandAnswers5012() {
        sbb.onEvent(req(HssSbb.S6A, 999, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        assertEquals(5012L, last().resultCode());
    }

    @Test
    void cxMarResyncViaStructuredAuthDataItemAnswers2001() {
        byte[] rand = new byte[16];
        for (int i = 0; i < 16; i++) {
            rand[i] = (byte) i;
        }
        byte[] ki = Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
        byte[] opc = Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1");
        byte[] amfStar = {0x00, 0x00};
        byte[] sqnMs = Bytes.sqnToBytes(500);
        et.epc.crypto.Milenage.Result aksSource =
                et.epc.crypto.Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        et.epc.crypto.Milenage.Result macSource =
                et.epc.crypto.Milenage.compute(ki, opc, rand, sqnMs, amfStar);
        byte[] auts = Bytes.concat(Bytes.xor(sqnMs, aksSource.aks()), macSource.macS());
        String randAutsHex = Bytes.toHex(Bytes.concat(rand, auts));
        List<DiameterAvp> structured = List.of(DiameterAvp.grouped3gpp(CxAvp.SIP_AUTH_DATA_ITEM,
                List.of(DiameterAvp.utf8(CxAvp.SIP_AUTHORIZATION, DiameterAvp.VENDOR_3GPP,
                        randAutsHex, true))));
        sbb.onEvent(reqStructured(HssSbb.CX, 303, Map.of(S6aAvp.USER_NAME, IMSI.digits()),
                structured), null);
        assertEquals(2001L, last().resultCode());
    }

    @Test
    void cxMarBadAutsAnswers4181Experimental() {
        byte[] bad = new byte[30];
        List<DiameterAvp> structured = List.of(DiameterAvp.grouped3gpp(CxAvp.SIP_AUTH_DATA_ITEM,
                List.of(DiameterAvp.utf8(CxAvp.SIP_AUTHORIZATION, DiameterAvp.VENDOR_3GPP,
                        Bytes.toHex(bad), true))));
        sbb.onEvent(reqStructured(HssSbb.CX, 303, Map.of(S6aAvp.USER_NAME, IMSI.digits()),
                structured), null);
        SendDiameterAnswer ans = last();
        assertEquals(0L, ans.resultCode(), "4181 leaves as Experimental-Result, no Result-Code");
        DiameterAvp exp = ans.avpsStructured().stream()
                .filter(a -> a.code() == 297).findFirst().orElseThrow();
        assertEquals(4181L, exp.children().stream().filter(c -> c.code() == 298).findFirst()
                .orElseThrow().asUnsigned32());
    }
}
