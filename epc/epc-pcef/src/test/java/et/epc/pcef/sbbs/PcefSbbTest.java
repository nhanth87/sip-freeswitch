package et.epc.pcef.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.api.Imsi;
import et.epc.pcef.Cdr;
import et.epc.pcef.CdrStore;
import et.epc.pcef.ChargingId;
import et.epc.pcef.GyHandler;
import et.epc.pcef.PcefService;
import et.epc.pcef.UsageRecord;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PCEF decision flow + Gx rail driven directly, without a SLEE container. */
class PcefSbbTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private final List<OutboundCommand> diameterSent = new ArrayList<>();
    private final RaCommandPort diameterPort = diameterSent::add;

    private PcefService pcef;
    private PcefSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        pcef = new PcefService();
        pcef.setPcrfLive(false);
        sbb = new PcefSbb.$Concrete(pcef);
        inject(sbb, "diameterRa", diameterPort);
        diameterSent.clear();
    }

    @Test
    void openSessionReturnsChargingId() {
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);
        assertEquals(1L, id.value());
        assertEquals(1L, sbb.getChargingId());
    }

    @Test
    void authorizeDecidesByApnWhenPcrfNotLive() {
        assertEquals("ims-qci5", sbb.authorize(IMSI, "ims").ruleName());
        assertEquals("static-default", sbb.authorize("internet").ruleName());
        assertEquals("static-default", sbb.authorize(IMSI, "internet").ruleName());
    }

    @Test
    void reportUsageAndClosePersistCdr() {
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);
        sbb.reportUsage(new UsageRecord(IMSI, "internet", id, 100, 200));
        Cdr cdr = sbb.close(id);
        assertEquals(300, cdr.uplinkOctets() + cdr.downlinkOctets());
        assertEquals(id, cdr.chargingId());
        assertTrue(pcef.reconcile(new UsageRecord(IMSI, "internet", id, 100, 200), cdr));
    }

    @Test
    void gxRarIsAcknowledgedWithSuccessfulRaa() {
        sbb.onEvent(new DiameterRequestEvent("sess-1", PcefSbb.GX_APP_ID, PcefSbb.RAR_CMD,
                11L, 22L, "o", "r", "d", "r", Map.of()), null);

        SendDiameterAnswer ans = lastDiameter();
        assertEquals(PcefSbb.GX_APP_ID, ans.applicationId());
        assertEquals(PcefSbb.RAR_CMD, ans.commandCode());
        assertEquals(SendDiameterAnswer.SUCCESS, ans.resultCode());
    }

    @Test
    void unknownGxCommandAnswersApplicationUnsupported() {
        sbb.onEvent(new DiameterRequestEvent("sess-2", PcefSbb.GX_APP_ID, 999,
                11L, 22L, "o", "r", "d", "r", Map.of()), null);

        SendDiameterAnswer ans = lastDiameter();
        assertEquals(PcefSbb.APP_UNSUPPORTED, ans.resultCode());
    }

    @Test
    void nonGxEventIsIgnored() {
        sbb.onEvent(new DiameterRequestEvent("sess-3", 16_777_236L /*Rx*/, 265,
                1L, 2L, "o", "r", "d", "r", Map.of()), null);
        assertTrue(diameterSent.isEmpty());
    }

    @Test
    void gxRarConsumesStructuredRuleInstallAndRemove() {
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);

        List<DiameterAvp> install = List.of(DiameterAvp.grouped3gpp(1001, List.of(
                DiameterAvp.grouped3gpp(1003, List.of(
                        DiameterAvp.utf8(1005, "ims-voice-qci1"))))));
        sbb.onEvent(new DiameterRequestEvent("sess-rar-1", PcefSbb.GX_APP_ID, PcefSbb.RAR_CMD,
                11L, 22L, "o", "r", "d", "r", Map.of(), install), null);

        SendDiameterAnswer ans = lastDiameter();
        assertEquals(PcefSbb.RAR_CMD, ans.commandCode());
        assertEquals(SendDiameterAnswer.SUCCESS, ans.resultCode());
        assertEquals(List.of("ims-voice-qci1"), pcef.rulesFor(id.value()));

        List<DiameterAvp> remove = List.of(DiameterAvp.grouped3gpp(1002, List.of(
                DiameterAvp.utf8(1005, "ims-voice-qci1"))));
        sbb.onEvent(new DiameterRequestEvent("sess-rar-2", PcefSbb.GX_APP_ID, PcefSbb.RAR_CMD,
                11L, 22L, "o", "r", "d", "r", Map.of(), remove), null);

        assertTrue(pcef.rulesFor(id.value()).isEmpty());
    }

    @Test
    void gyCcrInitialCarriesGroupedCreditControl() {
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);
        SendDiameterRequest req = sbb.buildCcrInitial(id, 5_000_000L);

        assertEquals(GyHandler.APP_ID, req.applicationId());
        assertEquals(GyHandler.CCR_CMD, req.commandCode());
        assertEquals("gy-" + id.value(), req.sessionId());
        assertEquals(GyHandler.REQ_TYPE_INITIAL, req.avps().get(GyHandler.CC_REQUEST_TYPE));

        DiameterAvp subscriptionId = child(req.avpsStructured(), GyHandler.SUBSCRIPTION_ID);
        assertNotNull(subscriptionId, "CCR-i must carry Subscription-Id(443)");
        DiameterAvp mscc = child(req.avpsStructured(), GyHandler.MULTIPLE_SERVICES_CREDIT_CONTROL);
        assertNotNull(mscc, "CCR-i must carry grouped Multiple-Services-Credit-Control(456)");
        DiameterAvp requested = child(children(mscc), GyHandler.REQUESTED_SERVICE_UNIT);
        assertNotNull(requested, "MSCC must carry Requested-Service-Unit(437)");
        assertEquals(5_000_000L,
                child(children(requested), GyHandler.CC_TOTAL_OCTETS).asUnsigned32());
    }

    @Test
    void gyCcaConsumeAppliesGrantedQuota() {
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);

        List<DiameterAvp> body = List.of(DiameterAvp.grouped(
                GyHandler.MULTIPLE_SERVICES_CREDIT_CONTROL, List.of(
                        DiameterAvp.grouped(GyHandler.GRANTED_SERVICE_UNIT, List.of(
                                DiameterAvp.u32(GyHandler.CC_TOTAL_OCTETS, 7_500_000L))))));
        DiameterAnswerEvent answer = new DiameterAnswerEvent(
                "gy-" + id.value(), GyHandler.APP_ID, GyHandler.CCR_CMD,
                2001L, 11L, 22L, "ocs.host", "epc.mno", Map.of(), body);

        GyHandler.Gcca gcca = sbb.handleCca(answer);
        assertEquals(2001L, gcca.resultCode());
        assertEquals(7_500_000L, gcca.grantedOctets());
        assertEquals(7_500_000L, pcef.grantedOctets(id));
    }

    @Test
    void gyCcaWithUnknownSessionDropsGrant() {
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);
        List<DiameterAvp> body = List.of(DiameterAvp.grouped(
                GyHandler.MULTIPLE_SERVICES_CREDIT_CONTROL, List.of(
                        DiameterAvp.grouped(GyHandler.GRANTED_SERVICE_UNIT, List.of(
                                DiameterAvp.u32(GyHandler.CC_TOTAL_OCTETS, 9_000L))))));
        DiameterAnswerEvent answer = new DiameterAnswerEvent(
                "gy-999", GyHandler.APP_ID, GyHandler.CCR_CMD,
                2001L, 1L, 2L, "ocs.host", "epc.mno", Map.of(), body);

        sbb.handleCca(answer);
        assertEquals(0L, pcef.grantedOctets(id),
                "grant for an unknown charging-id must be dropped");
    }

    @Test
    void cdrStartInterimStopReconcileThroughStore() {
        List<Cdr> persisted = new ArrayList<>();
        CdrStore store = new CdrStore() {
            @Override public void persist(Cdr cdr) {
                persisted.add(cdr);
            }
            @Override public List<Cdr> list() {
                return List.copyOf(persisted);
            }
        };
        PcefService service = new PcefService(store);
        ChargingId id = service.open(IMSI, "internet", PcefService.Product.POSTPAID);
        service.reportUsage(new UsageRecord(IMSI, "internet", id, 100, 200));
        service.close(id);

        assertEquals(3, persisted.size());
        assertEquals(Cdr.Kind.START, persisted.get(0).kind());
        assertEquals(Cdr.Kind.INTERIM, persisted.get(1).kind());
        assertEquals(Cdr.Kind.STOP, persisted.get(2).kind());
        assertEquals(300, persisted.get(2).uplinkOctets() + persisted.get(2).downlinkOctets());
        assertTrue(persisted.get(2).chargingId().equals(id));
    }

    @Test
    void chargingIdNoneGrantIsNoOpAndStableAcrossGxGy() {
        PcefService local = new PcefService();
        local.applyGrant(ChargingId.NONE, 1_000L);
        assertEquals(0, ChargingId.NONE.value());
        // Charging-id stability: the same id is used by the PCEF for Gx decisions
        // and Gy credit-control requests (never the NONE sentinel).
        ChargingId id = sbb.openSession(IMSI, "internet", PcefService.Product.POSTPAID);
        SendDiameterRequest req = sbb.buildCcrInitial(id, 1L);
        assertEquals(id.value(), Long.parseLong(req.sessionId().substring("gy-".length())));
    }

    private static DiameterAvp child(List<DiameterAvp> avps, int code) {
        for (DiameterAvp a : avps) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }

    private static List<DiameterAvp> children(DiameterAvp avp) {
        return avp.children() == null ? List.of() : avp.children();
    }

    private SendDiameterAnswer lastDiameter() {
        assertTrue(!diameterSent.isEmpty(), "expected an outbound Diameter answer");
        return (SendDiameterAnswer) diameterSent.get(diameterSent.size() - 1);
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = PcefSbb.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}