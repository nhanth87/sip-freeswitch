package et.epc.hss.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
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
import et.epc.hss.S6aSubscriptionData;
import et.epc.hss.SubscriberRecord;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HSS Diameter gateway SBB demux + procedure glue, driven without a container. */
class HssSbbTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort recordingPort = sent::add;
    private HssSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        HeapHssRepository repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(new SubscriberRecord(new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet(), ApnConfig.ims("192.0.2.10")), "internet",
                QosProfile.pilotDefault(), 0), 0));
        HssService hss = new HssService(repo, secrets, HOME);
        CxService cx = new CxService(repo, secrets);
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        sbb = new HssSbb.$Concrete(hss, cx, null);
        injectPort(sbb);
    }

    private void injectPort(HssSbb target) throws Exception {
        Field f = HssSbb.class.getDeclaredField("diameterRa");
        f.setAccessible(true);
        f.set(target, recordingPort);
    }

    private static DiameterRequestEvent req(long appId, int cc, Map<Integer, String> avps) {
        return new DiameterRequestEvent("sess-1", appId, cc, 9L, 10L,
                "mme.host", "epc.mno", "hss.epc", "epc.lab", avps);
    }

    private SendDiameterAnswer last() {
        assertFalse(sent.isEmpty(), "expected at least one answer");
        return (SendDiameterAnswer) sent.get(sent.size() - 1);
    }

    @Test
    void s6aAirRoutesToHssAndAnswersAia() {
        Map<Integer, String> avps = Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                S6aAvp.VISITED_PLMN_ID, Bytes.toHex(HOME.snId()));
        sbb.onEvent(req(HssSbb.S6A, 318, avps), null);
        assertEquals(1, sent.size());
        SendDiameterAnswer ans = last();
        assertEquals(HssSbb.S6A, ans.applicationId());
        assertEquals(318, ans.commandCode());
        assertEquals(2001L, ans.resultCode());
        assertTrue(ans.avps().containsKey(S6aAvp.KASME));
        assertEquals(IMSI.digits(), ans.avps().get(S6aAvp.USER_NAME));
    }

    @Test
    void s6aUlrCarriesFullSubscriptionData() {
        sbb.onEvent(req(HssSbb.S6A, 316, Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                S6aAvp.ORIGIN_HOST, "mme.host.mno")), null);
        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        String sd = ans.avps().get(S6aAvp.SUBSCRIPTION_DATA);
        assertTrue(sd.contains("\"internet\""));
        assertTrue(sd.contains("\"ims\""));
        assertEquals("0", ans.avps().get(S6aSubscriptionData.SUBSCRIBER_STATUS));
        assertEquals("2", ans.avps().get(S6aSubscriptionData.NETWORK_ACCESS_MODE));
    }

    @Test
    void s6aUnknownImsiAnswersUserUnknown() {
        sbb.onEvent(req(HssSbb.S6A, 318, Map.of(S6aAvp.USER_NAME, "001010000000099")), null);
        assertEquals(5001L, last().resultCode());
    }

    @Test
    void cxUarReturnsLocatedScscf() {
        sbb.onEvent(req(HssSbb.CX, 300, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        SendDiameterAnswer ans = last();
        // Subscriber already has an S-CSCF bound in setUp -> SUBSEQUENT_REGISTRATION (2003).
        assertEquals(2003L, ans.resultCode());
        assertEquals("sip:scscf.ims.lab:5060", ans.avps().get(CxAvp.SERVER_NAME));
    }

    @Test
    void cxSarAssignsScscf() {
        sbb.onEvent(req(HssSbb.CX, 301, Map.of(
                S6aAvp.USER_NAME, IMSI.digits(),
                CxAvp.SERVER_NAME, "sip:scscf2:5060",
                CxAvp.SERVER_ASSIGNMENT_TYPE, "1")), null);
        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        assertEquals("sip:scscf2:5060", ans.avps().get(CxAvp.SERVER_NAME));
    }

    @Test
    void cxMarReturnsAuthenticationData() {
        sbb.onEvent(req(HssSbb.CX, 303, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        SendDiameterAnswer ans = last();
        assertEquals(2001L, ans.resultCode());
        assertTrue(ans.avps().containsKey(CxAvp.SIP_AUTHENTICATE));
    }

    @Test
    void gxAndRxDelegateToPcrf() throws Exception {
        HssSbb.$Concrete pcrfSbb = new HssSbb.$Concrete(null, null,
                r -> SendDiameterAnswer.ok(r.sessionId(), r.applicationId(), r.commandCode(),
                        r.hopByHopId(), r.endToEndId(), Map.of(268, "2001")));
        injectPort(pcrfSbb);
        sent.clear();

        pcrfSbb.onEvent(req(HssSbb.GX, 272, Map.of()), null);
        assertEquals(2001L, last().resultCode());

        pcrfSbb.onEvent(req(HssSbb.RX, 265, Map.of()), null);
        assertEquals(2, sent.size());
        assertEquals(2001L, last().resultCode());
    }

    @Test
    void unknownApplicationIdAnswers3007() {
        sbb.onEvent(req(4L, 272, Map.of()), null);
        assertEquals(3007L, last().resultCode());
    }

    @Test
    void s6aWithNullHssAnswers3002() throws Exception {
        HssSbb.$Concrete bare = new HssSbb.$Concrete(null, null, null);
        injectPort(bare);
        sent.clear();
        bare.onEvent(req(HssSbb.S6A, 318, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        assertEquals(3002L, last().resultCode());
    }

    @Test
    void nonDiameterEventIsIgnored() {
        sbb.onEvent(new SleeEvent() {}, null);
        assertEquals(0, sent.size());
    }

    @Test
    void cachesSessionAndApplicationOnCmp() {
        sbb.onEvent(req(HssSbb.CX, 300, Map.of(S6aAvp.USER_NAME, IMSI.digits())), null);
        assertEquals("sess-1", sbb.getSessionId());
        assertEquals((Long) HssSbb.CX, sbb.getApplicationId());
    }
}