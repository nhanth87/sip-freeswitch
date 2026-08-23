package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Server-initiated S6a (CLR / PUR / IDR) and Cx (RTR / PPR) emission: HSS/Cx build
 * {@link SendDiameterRequest} and push them through {@link DiameterSenderPort} (the
 * Diameter RA command-port seam). Structures asserted without a container.
 */
class S6aServerInitTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private final List<OutboundCommand> sent = new ArrayList<>();
    private HeapHssRepository repo;
    private CxService cx;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
        HeapSecretStore secrets = new HeapSecretStore();
        secrets.put(IMSI, Bytes.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc"),
                Bytes.parseHex("cd63cb71954a9f4fd48a2d31a7b7fba1"), Bytes.parseHex("8000"));
        repo.save(subscriber(IMSI));
        cx = new CxService(repo, secrets).withSender(sent::add);
    }

    private static SubscriberRecord subscriber(Imsi imsi) {
        SubscriberRecord rec = new SubscriberRecord(new SubscriberProfile(
                imsi, "1234567890", true, HOME,
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0), 0);
        rec.setImpi("impi@001.01");
        rec.setImpus(List.of("sip:user001@001.01"));
        return rec;
    }

    private HssService hssBoundToMme() {
        HssService hss = new HssService(repo, new HeapSecretStore(), HOME)
                .withSender(sent::add);
        hss.handle(S6aMessage.builder(S6aCommand.ULR, true)
                .putUtf8(S6aAvp.USER_NAME, IMSI.digits())
                .putUtf8(S6aAvp.ORIGIN_HOST, "mme.eps.mnc001.mcc001")
                .build());
        return hss;
    }

    @Test
    void clrRequestTargetsServingMmeAndCarriesCancellationType() {
        SendDiameterRequest clr = hssBoundToMme().clrRequest(IMSI);
        assertEquals(S6aCommand.APP_ID, clr.applicationId());
        assertEquals(S6aCommand.CLR.code, clr.commandCode());
        assertEquals("mme.eps.mnc001.mcc001", clr.destinationHost());
        assertEquals("mme.epc.lab", clr.destinationRealm());
        assertEquals(IMSI.digits(), clr.avps().get(S6aAvp.USER_NAME));
        assertNotNull(clr.avps().get(S6aAvp.CANCELLATION_TYPE));
    }

    @Test
    void pushClrEmitsThroughSeam() {
        hssBoundToMme().pushClr(IMSI);
        SendDiameterRequest clr = last();
        assertEquals(S6aCommand.CLR.code, clr.commandCode());
        assertEquals("mme.eps.mnc001.mcc001", clr.destinationHost());
    }

    @Test
    void purRequestTargetsServingMme() {
        SendDiameterRequest pur = hssBoundToMme().purRequest(IMSI);
        assertEquals(S6aCommand.APP_ID, pur.applicationId());
        assertEquals(S6aCommand.PUR.code, pur.commandCode());
        assertEquals("mme.eps.mnc001.mcc001", pur.destinationHost());
        assertEquals(IMSI.digits(), pur.avps().get(S6aAvp.USER_NAME));
    }

    @Test
    void pushPurEmitsThroughSeam() {
        hssBoundToMme().pushPur(IMSI);
        assertEquals(S6aCommand.PUR.code, last().commandCode());
    }

    @Test
    void idrRequestCarriesStructuredSubscriptionData() {
        SendDiameterRequest idr = hssBoundToMme().idrRequest(IMSI);
        assertEquals(S6aCommand.APP_ID, idr.applicationId());
        assertEquals(S6aCommand.IDR.code, idr.commandCode());
        assertEquals("mme.eps.mnc001.mcc001", idr.destinationHost());
        assertEquals(IMSI.digits(), idr.avps().get(S6aAvp.USER_NAME));
        assertTrue(hasStructured(idr, S6aAvp.SUBSCRIPTION_DATA), "IDR must carry grouped Subscription-Data");
    }

    @Test
    void pushIdrEmitsThroughSeam() {
        hssBoundToMme().pushIdr(IMSI);
        assertEquals(S6aCommand.IDR.code, last().commandCode());
    }

    @Test
    void rtrRequestTargetsBoundScscf() {
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        SendDiameterRequest rtr = cx.rtrRequest("impi@001.01", "sip:user001@001.01", 0);
        assertEquals(CxAvp.APPLICATION_ID, rtr.applicationId());
        assertEquals(CxAvp.RTR, rtr.commandCode());
        assertEquals("scscf.ims.lab:5060", rtr.destinationHost());
        assertEquals("ims.lab", rtr.destinationRealm());
        assertEquals("0", rtr.avps().get(CxAvp.DEREGISTRATION_REASON));
        assertEquals("sip:user001@001.01", rtr.avps().get(CxAvp.PUBLIC_IDENTITY));
    }

    @Test
    void pushRtrEmitsThroughSeam() {
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        cx.pushRtr("impi@001.01", "sip:user001@001.01", 2);
        SendDiameterRequest rtr = last();
        assertEquals(CxAvp.RTR, rtr.commandCode());
        assertEquals("scscf.ims.lab:5060", rtr.destinationHost());
    }

    @Test
    void pprRequestCarriesUserDataStructured() {
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        SendDiameterRequest ppr = cx.pprRequest("impi@001.01", "sip:user001@001.01", "{\"ifc\":1}");
        assertEquals(CxAvp.APPLICATION_ID, ppr.applicationId());
        assertEquals(CxAvp.PPR, ppr.commandCode());
        assertEquals("scscf.ims.lab:5060", ppr.destinationHost());
        assertEquals("{\"ifc\":1}", ppr.avps().get(CxAvp.USER_DATA));
        assertTrue(hasStructured(ppr, CxAvp.USER_DATA), "PPR must carry structured User-Data");
        assertTrue(hasStructured(ppr, CxAvp.PUBLIC_IDENTITY), "PPR must carry structured Public-Identity");
    }

    @Test
    void pushPprEmitsThroughSeam() {
        cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060");
        cx.pushPpr("impi@001.01", "sip:user001@001.01", "{\"ifc\":1}");
        assertEquals(CxAvp.PPR, last().commandCode());
    }

    private SendDiameterRequest last() {
        assertFalse(sent.isEmpty(), "expected at least one emitted SendDiameterRequest");
        OutboundCommand c = sent.get(sent.size() - 1);
        assertTrue(c instanceof SendDiameterRequest, "expected SendDiameterRequest, got " + c.getClass());
        return (SendDiameterRequest) c;
    }

    private static boolean hasStructured(SendDiameterRequest r, int code) {
        List<DiameterAvp> raw = r.avpsStructured();
        if (raw == null) {
            return false;
        }
        for (DiameterAvp a : raw) {
            if (a.code() == code) {
                return true;
            }
        }
        return false;
    }
}