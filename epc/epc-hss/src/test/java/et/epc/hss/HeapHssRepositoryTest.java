package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HeapHssRepositoryTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private HeapHssRepository repo;

    @BeforeEach
    void setUp() {
        repo = new HeapHssRepository();
    }

    private static SubscriberProfile profile() {
        return new SubscriberProfile(IMSI, "1234567890", true, new PlmnId("001", "01"),
                List.of(ApnConfig.internet()), "internet", QosProfile.pilotDefault(), 0);
    }

    @Test
    void saveThenFind() {
        repo.save(new SubscriberRecord(profile(), 0));
        assertTrue(repo.find(IMSI).isPresent());
        assertEquals(1, repo.size());
    }

    @Test
    void saveOverwrites() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.save(new SubscriberRecord(profile(), 99));
        assertEquals(1, repo.size());
        assertEquals(99, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void findUnknownEmpty() {
        assertTrue(repo.find(new Imsi("001010000000002")).isEmpty());
    }

    @Test
    void reserveSqnIncrements() {
        repo.save(new SubscriberRecord(profile(), 0));
        assertEquals(et.epc.crypto.Sqn.of(1, 1), repo.reserveSqn(IMSI));
        assertEquals(et.epc.crypto.Sqn.of(2, 2), repo.reserveSqn(IMSI));
    }

    @Test
    void reserveSqnUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> repo.reserveSqn(IMSI));
    }

    @Test
    void forceSqnMonotonic() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.forceSqn(IMSI, 7);
        assertEquals(7, repo.find(IMSI).orElseThrow().sqn());
        repo.forceSqn(IMSI, 3);
        assertEquals(7, repo.find(IMSI).orElseThrow().sqn());
    }

    @Test
    void servingMmePurgedAndScscfUpdates() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.setServingMme(IMSI, "mme.a");
        assertEquals("mme.a", repo.find(IMSI).orElseThrow().servingMmeHost());
        repo.setPurged(IMSI, true);
        assertTrue(repo.find(IMSI).orElseThrow().purged());
        repo.setImsScscf(IMSI, "sip:s@ims");
        assertEquals("sip:s@ims", repo.find(IMSI).orElseThrow().imsScscfUri());
    }

    @Test
    void updateUnknownImsiIsNoop() {
        repo.setServingMme(IMSI, "mme.a");
        repo.setPurged(IMSI, true);
        repo.setImsScscf(IMSI, "sip:s@ims");
        assertEquals(0, repo.size());
    }

    @Test
    void multipleSubscribersDistinct() {
        Imsi other = new Imsi("001010000000002");
        repo.save(new SubscriberRecord(profile(), 0));
        repo.save(new SubscriberRecord(new SubscriberProfile(other, "0987654321", true,
                new PlmnId("001", "01"), List.of(ApnConfig.internet()), "internet",
                QosProfile.pilotDefault(), 0), 0));
        assertEquals(2, repo.size());
        repo.setPurged(other, true);
        assertTrue(repo.find(other).orElseThrow().purged());
        assertFalse(repo.find(IMSI).orElseThrow().purged());
    }

    @Test
    void concurrentSavesConsistentSize() throws Exception {
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            final int n = i;
            Thread t = new Thread(() -> {
                Imsi imsi = new Imsi(String.format("00101%09d", n));
                repo.save(new SubscriberRecord(new SubscriberProfile(imsi, "x", true,
                        new PlmnId("001", "01"), List.of(ApnConfig.internet()), "internet",
                        QosProfile.pilotDefault(), 0), n));
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(50, repo.size());
    }

    /**
     * Every concurrent reservation must yield a unique SQN — the repo contract that
     * {@link JdbcHssRepository} satisfies durably with {@code UPDATE … RETURNING}.
     */
    @Test
    void concurrentReserveSqnNeverDuplicates() throws Exception {
        repo.save(new SubscriberRecord(profile(), 0));
        int threads = 16;
        int perThread = 500;
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    seen.add(repo.reserveSqn(IMSI));
                }
            });
            ts.add(t);
            t.start();
        }
        for (Thread t : ts) {
            t.join();
        }
        assertEquals(threads * perThread, seen.size(), "no duplicate SQN may be handed out");
        long expected = 0;
        for (int i = 0; i < threads * perThread; i++) {
            expected = et.epc.crypto.Sqn.next(expected);
        }
        assertEquals(expected, repo.find(IMSI).orElseThrow().sqn());
    }

    /** Relational APN rows replace the legacy CSV-era profile on read (mirror of JDBC path). */
    @Test
    void apnProfileRowsOverlayLegacyProfileOnFind() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.upsertApnProfile(IMSI, ApnConfig.internet(), 0, true);
        repo.upsertApnProfile(IMSI, ApnConfig.ims("192.0.2.10"), 0, false);
        SubscriberProfile p = repo.find(IMSI).orElseThrow().profile();
        // ordered by APN name, default flag honored
        assertEquals(List.of("ims", "internet"), p.apns().stream().map(ApnConfig::apn).toList());
        assertEquals("internet", p.defaultApn());
        assertTrue(p.apn("ims").ims());
    }

    @Test
    void apnProfileUpsertReplacesExistingRow() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.upsertApnProfile(IMSI, ApnConfig.internet(), 0, false);
        repo.upsertApnProfile(IMSI, ApnConfig.ims("192.0.2.10"), 0, true);
        SubscriberProfile p = repo.find(IMSI).orElseThrow().profile();
        assertEquals(List.of("ims", "internet"), p.apns().stream().map(ApnConfig::apn).toList());
        assertEquals("ims", p.defaultApn(), "latest upsert of (imsi, apn) wins the default flag");
    }

    @Test
    void deleteApnProfileFallsBackToLegacyProfile() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.upsertApnProfile(IMSI, ApnConfig.ims("192.0.2.10"), 0, true);
        repo.deleteApnProfile(IMSI, "ims");
        SubscriberProfile p = repo.find(IMSI).orElseThrow().profile();
        assertEquals(List.of("internet"), p.apns().stream().map(ApnConfig::apn).toList());
        assertEquals("internet", p.defaultApn());
    }

    @Test
    void findAllAppliesApnProfileOverlay() {
        repo.save(new SubscriberRecord(profile(), 0));
        repo.upsertApnProfile(IMSI, ApnConfig.ims("192.0.2.10"), 0, true);
        List<SubscriberRecord> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("ims", all.get(0).profile().defaultApn());
    }
}