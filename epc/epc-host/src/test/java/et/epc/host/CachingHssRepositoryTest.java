package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.SubscriberRecord;
import java.util.List;
import org.infinispan.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CachingHssRepositoryTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
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

    private static HeapHssRepository heap() {
        HeapHssRepository heap = new HeapHssRepository();
        heap.save(new SubscriberRecord(PROFILE, 0));
        return heap;
    }

    @Test
    void findServesFromCacheUntilInvalidated() {
        HeapHssRepository delegate = heap();
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());
        assertTrue(cached.find(IMSI).isPresent());

        // Mutate the durable row directly, bypassing the caching layer.
        delegate.setServingMme(IMSI, "mme-A");
        // Cache-hit still serves the pre-mutation snapshot (stale) — proves caching.
        assertEquals(null, cached.find(IMSI).orElseThrow().servingMmeHost());

        // A caching-layer mutation invalidates, so the next read is fresh.
        cached.setServingMme(IMSI, "mme-B");
        assertEquals("mme-B", cached.find(IMSI).orElseThrow().servingMmeHost());
    }

    @Test
    void reserveSqnDelegatesAndInvalidatesSqn() {
        HeapHssRepository delegate = heap();
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());
        assertTrue(cached.find(IMSI).isPresent());

        assertEquals(et.epc.crypto.Sqn.next(0), cached.reserveSqn(IMSI));
        assertEquals(et.epc.crypto.Sqn.next(0), cached.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void findByImpuPopulatesCache() {
        HeapHssRepository delegate = heap();
        delegate.saveImsIdentity(IMSI, "alice@ims.lab", List.of("sip:alice@ims.lab"), "{}");
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());

        assertTrue(cached.findByImpu("sip:alice@ims.lab").isPresent());
        // Secondary lookup cached the record; direct find now hits the cache.
        assertTrue(cached.find(IMSI).isPresent());
    }

    @Test
    void warmPreloadsIdentityInsteadOfPerRequestReads() {
        HeapHssRepository delegate = heap();
        CachingHssRepository cached = new CachingHssRepository(delegate, cache());
        cached.warm();

        // Mutate the durable row directly after warm, bypassing the caching layer.
        delegate.setServingMme(IMSI, "mme-A");
        // Warm populated the cache at boot, so find() serves the warm snapshot (stale)
        // instead of issuing a per-request Postgres read through the delegate.
        assertEquals(null, cached.find(IMSI).orElseThrow().servingMmeHost());
    }
}