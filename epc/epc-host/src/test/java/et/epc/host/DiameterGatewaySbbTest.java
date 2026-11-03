package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
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
import et.epc.hss.HssService;
import et.epc.hss.S6aAvp;
import et.epc.hss.PcrfPort;
import et.epc.hss.SubscriberRecord;
import et.epc.host.sbb.DiameterGatewaySbb;
import et.epc.pcrf.GxHandler;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.RxHandler;
import et.epc.pcrf.sbbs.PcrfSbb;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Diameter gateway SBB demux + procedure glue, driven without a container. */
class DiameterGatewaySbbTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private final List<OutboundCommand> sent = new ArrayList<>();

    private final RaCommandPort recordingPort = sent::add;

    private DiameterGatewaySbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        HeapHssRepository repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        HssService hss = new HssService(repo, secrets, HOME);
        CxService cx = new CxService(repo);
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        sbb = new DiameterGatewaySbb.$Concrete(hss, cx);
        inject(sbb, "diameterRa", recordingPort);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = DiameterGatewaySbb.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static DiameterRequestEvent req(long appId, int cc, Map<Integer, String> avps) {
        return new DiameterRequestEvent("sess-1", appId, cc, 9L, 10L,
                "mme.host", "epc.mno", "hss.epc", "epc.lab", avps);
    }

    private SendDiameterAnswer last() {
        assertEquals(1, sent.size(), "expected one answer");
        return (SendDiameterAnswer) sent.get(0);
    }

    @Test
    void s6aAirRoutesToHssAndAnswersAia() throws Exception {
        Map<Integer, String> avps = Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                S6aAvp.VISITED_PLMN_ID, Bytes.toHex(HOME.snId()));
        sbb.onEvent(req(DiameterGatewaySbb.S6A, 318, avps), null);
        SendDiameterAnswer ans = last();
        assertEquals(DiameterGatewaySbb.S6A, ans.applicationId());
        assertEquals(318, ans.commandCode());
        assertEquals(2001L, ans.resultCode());
        assertTrue(ans.avps().containsKey(S6aAvp.KASME));
        assertEquals(IMSI.digits(), ans.avps().get(S6aAvp.USER_NAME));
    }

    @Test
    void s6aUnknownImsiAnswersUserUnknown() throws Exception {
        Map<Integer, String> avps = Map.of(S6aAvp.USER_NAME, "001010000000099");
        sbb.onEvent(req(DiameterGatewaySbb.S6A, 318, avps), null);
        SendDiameterAnswer ans = last();
        assertEquals(5001L, ans.resultCode());
    }

    @Test
    void cxUarReturnsLocatedScscf() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.CX, 300, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        SendDiameterAnswer ans = last();
        // TS 29.229: SUBSEQUENT_REGISTRATION (S-CSCF already bound) is an Experimental-Result
        // {Vendor-Id 10415, 2002} with no Result-Code; Server-Name is a typed 3GPP AVP.
        assertEquals(0L, ans.resultCode(), "no Result-Code AVP on a UAA");
        com.microjainslee.ra.diameter.avp.DiameterAvp exp = ans.avpsStructured().stream()
                .filter(a -> a.code() == 297).findFirst().orElseThrow();
        assertEquals(10_415L, exp.children().stream().filter(c -> c.code() == 266).findFirst()
                .orElseThrow().asUnsigned32());
        assertEquals(2002L, exp.children().stream().filter(c -> c.code() == 298).findFirst()
                .orElseThrow().asUnsigned32());
        com.microjainslee.ra.diameter.avp.DiameterAvp server = ans.avpsStructured().stream()
                .filter(a -> a.code() == 602).findFirst().orElseThrow();
        assertEquals(10_415L, server.vendorId());
        assertEquals("sip:scscf.ims.lab:5060", server.asUtf8());
        assertTrue(ans.avps().isEmpty(), "no untyped flat AVPs on the wire");
    }

    @Test
    void cxSarAssignsScscf() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.CX, 301,
                Map.of(S6aAvp.USER_NAME, IMSI.digits(), 602, "sip:scscf2:5060")), null);
        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        assertEquals("sip:scscf2:5060", typed(ans, 602).asUtf8());
    }

    @Test
    void cxUnknownCommandAnswersError() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.CX, 999, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        SendDiameterAnswer ans = last();
        assertEquals(5012L, ans.resultCode()); // UNABLE_TO_COMPLY, not USER_UNKNOWN
    }

    @Test
    void unknownApplicationIdAnswers3007() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.GX, 272, Map.of()), null);
        SendDiameterAnswer ans = last();
        assertEquals(3007L, ans.resultCode());
    }

    @Test
    void s6aWithNullHssAnswers3002() throws Exception {
        DiameterGatewaySbb.$Concrete bare = new DiameterGatewaySbb.$Concrete(null, null);
        inject(bare, "diameterRa", recordingPort);
        sent.clear();
        bare.onEvent(req(DiameterGatewaySbb.S6A, 318, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        assertEquals(3002L, last().resultCode());
    }

    @Test
    void nonDiameterEventIsIgnored() throws Exception {
        sbb.onEvent(new com.microjainslee.api.SleeEvent() {}, null);
        assertEquals(0, sent.size());
    }

    @Test
    void cachesSessionAndApplicationOnCmp() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.CX, 300, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        assertEquals("sess-1", sbb.getSessionId());
        assertEquals((Long) DiameterGatewaySbb.CX, sbb.getApplicationId());
    }

    @Test
    void cxMarReturnsAuthVector() throws Exception {
        HeapHssRepository repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0));
        CxService cxWithSecrets = new CxService(repo, secrets);
        DiameterGatewaySbb.$Concrete gate = new DiameterGatewaySbb.$Concrete(null, cxWithSecrets);
        inject(gate, "diameterRa", recordingPort);
        gate.onEvent(req(DiameterGatewaySbb.CX, 303, Map.of(CxAvp.USER_NAME, IMSI.digits())), null);
        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        // On the wire the vector is the grouped SIP-Auth-Data-Item(612), 3GPP vendor leaves.
        assertEquals("Digest-AKAv1-MD5", typed(ans, CxAvp.SIP_AUTHENTICATION_SCHEME).asUtf8());
        assertNotNull(typed(ans, CxAvp.SIP_AUTHENTICATE));
        assertNotNull(typed(ans, CxAvp.SIP_AUTHORIZATION));
        assertNotNull(typed(ans, CxAvp.CONFIDENTIALITY_KEY));
        assertNotNull(typed(ans, CxAvp.INTEGRITY_KEY));
    }

    @Test
    void cxSarReturnsServerNameAndResult() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.CX, 301, Map.of(
                CxAvp.USER_NAME, IMSI.digits(),
                CxAvp.SERVER_NAME, "sip:scscf.ims.lab:5060")), null);
        SendDiameterAnswer ans = last();
        assertEquals(DiameterGatewaySbb.CX, ans.applicationId());
        assertEquals(301, ans.commandCode());
        assertEquals(2001L, ans.resultCode());
        assertEquals("sip:scscf.ims.lab:5060", typed(ans, CxAvp.SERVER_NAME).asUtf8());
    }

    /** A typed AVP of the answer, searched through grouped AVPs; fails when absent. */
    private static com.microjainslee.ra.diameter.avp.DiameterAvp typed(SendDiameterAnswer ans, int code) {
        java.util.ArrayDeque<com.microjainslee.ra.diameter.avp.DiameterAvp> todo =
                new java.util.ArrayDeque<>(ans.avpsStructured());
        while (!todo.isEmpty()) {
            var a = todo.poll();
            if (a.code() == code) {
                return a;
            }
            if (a.children() != null) {
                todo.addAll(a.children());
            }
        }
        throw new AssertionError("AVP " + code + " not in the answer");
    }

    @Test
    void gxCcrRoutesToPcrfAndAnswersCca() throws Exception {
        PcrfService service = PcrfService.withHeapPolicy();
        PcrfSbb.$Concrete pcrfSbb = new PcrfSbb.$Concrete(service);
        PcrfPort pcrfPort = pcrfSbb::handle;
        DiameterGatewaySbb.$Concrete gated = new DiameterGatewaySbb.$Concrete(null, null, pcrfPort);
        inject(gated, "diameterRa", recordingPort);
        gated.onEvent(req(DiameterGatewaySbb.GX, GxHandler.CCR_CMD, Map.of(
                GxHandler.SUBSCRIPTION_ID_DATA, IMSI.digits(),
                GxHandler.CALLED_STATION_ID, "internet",
                GxHandler.CC_REQUEST_TYPE, GxHandler.REQ_TYPE_INITIAL,
                GxHandler.CC_REQUEST_NUMBER, "1")), null);
        SendDiameterAnswer ans = last();
        assertEquals(DiameterGatewaySbb.GX, ans.applicationId());
        assertEquals(GxHandler.CCR_CMD, ans.commandCode());
        assertEquals(2001L, ans.resultCode());
        assertEquals(GxHandler.RESULT_SUCCESS, ans.avps().get(GxHandler.RESULT_CODE));
        assertEquals(GxHandler.REQ_TYPE_INITIAL, ans.avps().get(GxHandler.CC_REQUEST_TYPE));
        assertEquals("1", ans.avps().get(GxHandler.CC_REQUEST_NUMBER));
        assertTrue(ans.avps().get(GxHandler.DEFAULT_EPS_BEARER_QOS).contains("qci="));
        assertTrue(ans.avps().get(GxHandler.USAGE_MONITORING_INFORMATION).contains("pilot-volume"));
    }

    @Test
    void rxAarRoutesToPcrf() throws Exception {
        PcrfService service = PcrfService.withHeapPolicy();
        PcrfSbb.$Concrete pcrfSbb = new PcrfSbb.$Concrete(service);
        PcrfPort pcrfPort = pcrfSbb::handle;
        DiameterGatewaySbb.$Concrete gated = new DiameterGatewaySbb.$Concrete(null, null);
        gated.withPcrf(pcrfPort);
        inject(gated, "diameterRa", recordingPort);
        gated.onEvent(req(DiameterGatewaySbb.RX, RxHandler.AA_CMD, Map.of(
                RxHandler.FRAMED_IP_ADDRESS, "10.0.0.5",
                RxHandler.MEDIA_TYPE, "audio",
                RxHandler.AF_APPLICATION_IDENTIFIER, "volte")), null);
        SendDiameterAnswer ans = last();
        assertEquals(DiameterGatewaySbb.RX, ans.applicationId());
        assertEquals(RxHandler.AA_CMD, ans.commandCode());
        assertEquals(2001L, ans.resultCode());
        assertEquals(GxHandler.RESULT_SUCCESS, ans.avps().get(RxHandler.RESULT_CODE));
        assertEquals("volte", ans.avps().get(RxHandler.AF_APPLICATION_IDENTIFIER));
    }

    @Test
    void gxWithNullPcrfStillErrors3007() throws Exception {
        sbb.onEvent(req(DiameterGatewaySbb.GX, GxHandler.CCR_CMD,
                Map.of(GxHandler.SUBSCRIPTION_ID_DATA, IMSI.digits())), null);
        assertEquals(3007L, last().resultCode());
    }
}