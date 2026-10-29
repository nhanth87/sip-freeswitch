package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HssRepository;
import et.epc.hss.SubscriberRecord;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.infinispan.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Phase D cache hardening tests for {@link CachingHssRepository}: negative
 * unknown-IMSI/IMPU entries with a short TTL, the IMPU→IMSI index for Cx
 * lookups, and the SQN/secrets-never-cached lock.
 */
class CachingHssRepositoryCacheTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final Imsi UNKNOWN_IMSI = new Imsi("001019999999999");
    private static final SubscriberProfile PROFILE = new SubscriberProfile(
            IMSI, "1234567890", true, new PlmnId("001", "01"),
            List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0);

    private EpcInfinispanManager mgr;

    @AfterEach
    void stop() {
        if (mgr != null) {
            mgr.stop();
            mgr = null;
        }
    }

    private Cache<String, CachingHssRepository.CachedSubscriber> cache() {
        EpcInfinispanManager m = new EpcInfinispanManager();
        m.enabled = true;
        m.mode = "LOCAL";
        m.clusterName = "epc-cluster";
        m.nodeId = "epc-1";
        m.ttlSeconds = 60;
        m.start();
        mgr = m;
        return m.cache(EpcInfinispanManager.CACHE_SUBSCRIBERS);
    }

    /** Delegate with per-method call counters to prove where reads are served. */
    static final class CountingRepo implements HssRepository {
        final HeapHssRepository heap = new HeapHssRepository();
        int finds;
        int findByImpus;
        int reserveSqns;

        @Override public void save(SubscriberRecord rec) { heap.save(rec); }
        @Override public Optional<SubscriberRecord> find(Imsi imsi) {
            finds++;
            return heap.find(imsi);
        }
        @Override public long reserveSqn(Imsi imsi) {
            reserveSqns++;
            return heap.reserveSqn(imsi);
        }
        @Override public void forceSqn(Imsi imsi, long next) { heap.forceSqn(imsi, next); }
        @Override public void setServingMme(Imsi imsi, String host) { heap.setServingMme(imsi, host); }
        @Override public void setPurged(Imsi imsi, boolean purged) { heap.setPurged(imsi, purged); }
        @Override public void setImsScscf(Imsi imsi, String uri) { heap.setImsScscf(imsi, uri); }
        @Override public Optional<SubscriberRecord> findByImpi(String impi) { return heap.findByImpi(impi); }
        @Override public Optional<SubscriberRecord> findByImpu(String impu) {
            findByImpus++;
            return heap.findByImpu(impu);
        }
        @Override public void saveImsIdentity(Imsi imsi, String impi, List<String> impus, String json) {
            heap.saveImsIdentity(imsi, impi, impus, json);
        }
        @Override public void upsertApnProfile(Imsi imsi, ApnConfig apn, int cc, boolean def) {
            heap.upsertApnProfile(imsi, apn, cc, def);
        }
        @Override public void deleteApnProfile(Imsi imsi, String apn) { heap.deleteApnProfile(imsi, apn); }
        @Override public void setImpuRegState(Imsi imsi, String state, String uri) {
            heap.setImpuRegState(imsi, state, uri);
        }
        @Override public String locateScscf(Imsi imsi) { return heap.locateScscf(imsi); }
        @Override public List<SubscriberRecord> findAll() { return heap.findAll(); }
        @Override public int size() { return heap.size(); }
    }

    private static CountingRepo seeded() {
        CountingRepo repo = new CountingRepo();
        repo.heap.save(new SubscriberRecord(PROFILE, 0));
        return repo;
    }

    @Test
    void negativeImsiEntryStopsDelegateHammeringAndExpires() throws Exception {
        CountingRepo delegate = new CountingRepo(); // empty store: IMSI unknown
        CachingHssRepository cached = new CachingHssRepository(delegate, cache(), 150L);

        assertTrue(cached.find(UNKNOWN_IMSI).isEmpty());
        assertEquals(1, delegate.finds);
        // Repeat lookups are served by the negative entry — no delegate hit.
        assertTrue(cached.find(UNKNOWN_IMSI).isEmpty());
        assertTrue(cached.find(UNKNOWN_IMSI).isEmpty());
        assertEquals(1, delegate.finds);

        // Short TTL expires; the next lookup re-checks the durable store.
        Thread.sleep(400);
        assertTrue(cached.find(UNKNOWN_IMSI).isEmpty());
        assertEquals(2, delegate.finds);
    }

    @Test
    void negativeImpuEntryStopsDelegateHammeringAndExpires() throws Exception {
        CountingRepo delegate = seeded();
        CachingHssRepository cached = new CachingHssRepository(delegate, cache(), 150L);

        assertTrue(cached.findByImpu("sip:nobody@ims.lab").isEmpty());
        assertEquals(1, delegate.findByImpus);
        assertTrue(cached.findByImpu("sip:nobody@ims.lab").isEmpty());
        assertEquals(1, delegate.findByImpus);

        Thread.sleep(400);
        assertTrue(cached.findByImpu("sip:nobody@ims.lab").isEmpty());
        assertEquals(2, delegate.findByImpus);
    }

    @Test
    void sqnAndSecretsNeverCached() {
        // Structural lock: the ISPN snapshot carries no SQN and no secret material.
        Set<String> banned = Set.of("sqn", "ki", "opc", "op", "amf", "secret", "kek");
        for (RecordComponent rc : CachingHssRepository.CachedSubscriber.class.getRecordComponents()) {
            assertFalse(banned.contains(rc.getName().toLowerCase(Locale.ROOT)),
                    "snapshot must not carry " + rc.getName());
        }
        for (RecordComponent rc : CachingHssRepository.ImpuIndex.class.getRecordComponents()) {
            assertFalse(banned.contains(rc.getName().toLowerCase(Locale.ROOT)),
                    "index entry must not carry " + rc.getName());
        }

        // Behavioral lock: SQN reservation always goes to the durable store, and a
        // cache-served read never carries SQN (snapshot has no SQN column).
        CountingRepo delegate = seeded();
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());
        assertTrue(cached.find(IMSI).isPresent()); // populates the cache (durable sqn=0)

        // Durable SQN advances behind the cache's back; the cache hit still reports
        // the non-authoritative 0 — proof the snapshot carries no SQN column.
        delegate.heap.forceSqn(IMSI, 42);
        assertEquals(0L, cached.find(IMSI).orElseThrow().sqn());

        // Reservation never reads from cache.
        assertEquals(et.epc.crypto.Sqn.next(42), cached.reserveSqn(IMSI));
        assertEquals(1, delegate.reserveSqns);
        assertEquals(et.epc.crypto.Sqn.next(et.epc.crypto.Sqn.next(42)), cached.reserveSqn(IMSI));
        assertEquals(2, delegate.reserveSqns);
    }

    @Test
    void impuIndexServesCxLookupsWithoutDelegate() {
        CountingRepo delegate = seeded();
        delegate.heap.saveImsIdentity(IMSI, "alice@ims.lab", List.of("sip:alice@ims.lab"), "{}");
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());

        assertEquals(IMSI, cached.findByImpu("sip:alice@ims.lab").orElseThrow().imsi());
        assertEquals(1, delegate.findByImpus);

        // Repeat lookups ride the IMPU→IMSI index + IMSI snapshot — no delegate hit,
        // regardless of sip:/case/whitespace input form.
        assertEquals(IMSI, cached.findByImpu("sip:alice@ims.lab").orElseThrow().imsi());
        assertEquals(IMSI, cached.findByImpu("SIP:ALICE@IMS.LAB").orElseThrow().imsi());
        assertEquals(IMSI, cached.findByImpu("alice@ims.lab").orElseThrow().imsi());
        assertEquals(1, delegate.findByImpus);
    }

    @Test
    void warmIndexesImpusForBootTimeCxLookups() {
        CountingRepo delegate = seeded();
        delegate.heap.saveImsIdentity(IMSI, "alice@ims.lab", List.of("sip:alice@ims.lab"), "{}");
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());
        cached.warm();

        assertTrue(cached.find(IMSI).isPresent());
        assertEquals(IMSI, cached.findByImpu("sip:alice@ims.lab").orElseThrow().imsi());
        // Both served entirely from cache — zero delegate reads post-warm.
        assertEquals(0, delegate.finds);
        assertEquals(0, delegate.findByImpus);
    }

    @Test
    void invalidationCoversImpuIndexEntries() {
        CountingRepo delegate = seeded();
        delegate.heap.saveImsIdentity(IMSI, "alice@ims.lab", List.of("sip:alice@ims.lab"), "{}");
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());

        assertTrue(cached.findByImpu("sip:alice@ims.lab").isPresent());
        assertEquals(1, delegate.findByImpus);

        // Admin mutation through the caching layer: old IMPU index entries dropped,
        // new IMPUs indexed immediately. (IMPI moves too — the heap delegate also
        // matches findByImpu against the IMPI.)
        cached.saveImsIdentity(IMSI, "alice2@ims.lab", List.of("sip:alice2@ims.lab"), "{}");

        // New IMPU resolves via the fresh index entry (no delegate findByImpu).
        assertEquals(IMSI, cached.findByImpu("sip:alice2@ims.lab").orElseThrow().imsi());
        assertEquals(1, delegate.findByImpus);

        // Old IMPU falls to the delegate once, then is negative-cached.
        assertTrue(cached.findByImpu("sip:alice@ims.lab").isEmpty());
        assertEquals(2, delegate.findByImpus);
        assertTrue(cached.findByImpu("sip:alice@ims.lab").isEmpty());
        assertEquals(2, delegate.findByImpus);
    }

    @Test
    void impuKeyNormalizationIsConsistent() {
        assertEquals(CachingHssRepository.impuKey("sip:alice@ims.lab"),
                CachingHssRepository.impuKey("SIP:ALICE@IMS.LAB"));
        assertEquals(CachingHssRepository.impuKey("alice@ims.lab"),
                CachingHssRepository.impuKey(" sip:alice@ims.lab "));
        assertTrue(CachingHssRepository.impuKey("alice@ims.lab")
                .startsWith(CachingHssRepository.IMPU_INDEX_PREFIX));
    }
}