package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class CxServiceTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private static SubscriberProfile profile() {
        return new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0);
    }

    @Test
    void assignThenLocate() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);
        assertEquals("sip:scscf.ims.lab:5060", cx.assignScscf(IMSI, "sip:scscf.ims.lab:5060"));
        assertEquals("sip:scscf.ims.lab:5060", cx.locatedScscf(IMSI));
    }

    @Test
    void assignOverwrites() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);
        cx.assignScscf(IMSI, "sip:one");
        cx.assignScscf(IMSI, "sip:two");
        assertEquals("sip:two", cx.locatedScscf(IMSI));
    }

    @Test
    void locatedUnknownReturnsNull() {
        CxService cx = new CxService(new HeapHssRepository());
        assertNull(cx.locatedScscf(IMSI));
    }

    @Test
    void doesNotTouchServingMme() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        repo.setServingMme(IMSI, "mme.a");
        CxService cx = new CxService(repo);
        cx.assignScscf(IMSI, "sip:scscf");
        assertEquals("mme.a", repo.find(IMSI).orElseThrow().servingMmeHost());
    }

    /* ------------------------------------------------------------------ *
     * IMPU registration state machine (TS 29.228 / TS 23.002) — Phase B.
     * ------------------------------------------------------------------ */

    @Test
    void sarRegistrationPersistsRegisteredStateAndScscf() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);

        CxService.CxResult r = cx.sar(IMSI.digits(), "sip:user@ims.lab",
                "sip:scscf-a.ims.lab:5060", CxAvp.SAT_REGISTRATION);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        assertEquals("sip:scscf-a.ims.lab:5060", r.avps().get(CxAvp.SERVER_NAME));

        SubscriberRecord rec = repo.find(IMSI).orElseThrow();
        assertEquals(CxService.STATE_REGISTERED, rec.impuRegState());
        assertEquals("sip:scscf-a.ims.lab:5060", rec.scscfUri());
        assertEquals("sip:scscf-a.ims.lab:5060", rec.imsScscfUri());
        assertEquals("sip:scscf-a.ims.lab:5060", repo.locateScscf(IMSI));
    }

    @Test
    void sarReRegistrationKeepsRegisteredState() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);

        cx.sar(IMSI.digits(), "sip:user@ims.lab", "sip:scscf-a.ims.lab:5060", CxAvp.SAT_REGISTRATION);
        CxService.CxResult r = cx.sar(IMSI.digits(), "sip:user@ims.lab",
                "sip:scscf-a.ims.lab:5060", CxAvp.SAT_RE_REGISTRATION);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        assertEquals(CxService.STATE_REGISTERED, repo.find(IMSI).orElseThrow().impuRegState());
        assertEquals("sip:scscf-a.ims.lab:5060", repo.locateScscf(IMSI));
    }

    @Test
    void rtrPersistsNotRegisteredAndClearsScscf() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);
        cx.sar(IMSI.digits(), "sip:user@ims.lab", "sip:scscf-a.ims.lab:5060", CxAvp.SAT_REGISTRATION);

        CxService.CxResult r = cx.rtr(IMSI.digits(), "sip:user@ims.lab", 0);
        assertEquals(CxAvp.SUCCESS, r.resultCode());

        SubscriberRecord rec = repo.find(IMSI).orElseThrow();
        assertEquals(CxService.STATE_NOT_REGISTERED, rec.impuRegState());
        assertNull(rec.scscfUri());
        assertNull(rec.imsScscfUri());
        assertNull(repo.locateScscf(IMSI));
    }

    @Test
    void sarPlainDeregistrationPersistsNotRegistered() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);
        cx.sar(IMSI.digits(), "sip:user@ims.lab", "sip:scscf-a.ims.lab:5060", CxAvp.SAT_REGISTRATION);

        CxService.CxResult r = cx.sar(IMSI.digits(), "sip:user@ims.lab",
                null, CxAvp.SAT_USER_DEREGISTRATION);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        SubscriberRecord rec2 = repo.find(IMSI).orElseThrow();
        assertEquals(CxService.STATE_NOT_REGISTERED, rec2.impuRegState());
        assertNull(repo.locateScscf(IMSI));
    }

    @Test
    void sarStoreServerNameDeregistrationKeepsUnregisteredWithServer() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);
        cx.sar(IMSI.digits(), "sip:user@ims.lab", "sip:scscf-a.ims.lab:5060", CxAvp.SAT_REGISTRATION);

        // TS 29.228 store-server-name deregistration: UNREGISTERED, server retained.
        cx.sar(IMSI.digits(), "sip:user@ims.lab", null, CxAvp.SAT_TIMEOUT_DEREGISTRATION_STORE_SERVER_NAME);
        SubscriberRecord rec = repo.find(IMSI).orElseThrow();
        assertEquals(CxService.STATE_UNREGISTERED, rec.impuRegState());
        assertEquals("sip:scscf-a.ims.lab:5060", rec.scscfUri());
        assertEquals("sip:scscf-a.ims.lab:5060", repo.locateScscf(IMSI));
    }

    @Test
    void sarUnregisteredUserStoresServerNameAsUnregistered() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        CxService cx = new CxService(repo);

        // TS 29.228 UNREGISTERED_USER: HSS stores the Server-Name; user stays UNREGISTERED.
        CxService.CxResult r = cx.sar(IMSI.digits(), "sip:user@ims.lab",
                "sip:scscf-b.ims.lab:5060", CxAvp.SAT_UNREGISTERED_USER);
        assertEquals(CxAvp.SUCCESS, r.resultCode());
        SubscriberRecord rec = repo.find(IMSI).orElseThrow();
        assertEquals(CxService.STATE_UNREGISTERED, rec.impuRegState());
        assertEquals("sip:scscf-b.ims.lab:5060", rec.scscfUri());
        assertEquals("sip:scscf-b.ims.lab:5060", repo.locateScscf(IMSI));
    }

    @Test
    void lirReflectsRegistrationState() {
        HeapHssRepository repo = new HeapHssRepository();
        repo.save(new SubscriberRecord(profile(), 0));
        repo.saveImsIdentity(IMSI, "impi@001.01", List.of("sip:user@001.01"), "{}");
        CxService cx = new CxService(repo);

        // REGISTERED -> LIA carries the assigned S-CSCF.
        cx.sar(IMSI.digits(), "sip:user@001.01", "sip:scscf-a.ims.lab:5060", CxAvp.SAT_REGISTRATION);
        CxService.CxResult registered = cx.lir("sip:user@001.01");
        assertEquals(CxAvp.SUCCESS, registered.resultCode());
        assertEquals("sip:scscf-a.ims.lab:5060", registered.avps().get(CxAvp.SERVER_NAME));

        // UNREGISTERED with retained server-name -> LIA still locates it.
        cx.sar(IMSI.digits(), "sip:user@001.01", null, CxAvp.SAT_TIMEOUT_DEREGISTRATION_STORE_SERVER_NAME);
        CxService.CxResult unregistered = cx.lir("sip:user@001.01");
        assertEquals("sip:scscf-a.ims.lab:5060", unregistered.avps().get(CxAvp.SERVER_NAME));

        // RTR -> NOT_REGISTERED: stored server cleared; LIA falls back to the lab default.
        cx.rtr(IMSI.digits(), "sip:user@001.01", 0);
        assertNull(repo.locateScscf(IMSI));
        CxService.CxResult afterRtr = cx.lir("sip:user@001.01");
        assertEquals(CxAvp.SUCCESS, afterRtr.resultCode());
        assertEquals(CxService.DEFAULT_SCSCF, afterRtr.avps().get(CxAvp.SERVER_NAME));
    }
}