package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Fake sampler + sink: delta reporting, VOLTH trigger, PERIO gating. */
class UrrPollerTest {

    private static final class FakeSampler implements UrrCounterSampler {
        final Map<String, Long> counters = new LinkedHashMap<>();

        @Override
        public Map<String, Long> sample() {
            return Map.copyOf(counters);
        }
    }

    record Reported(long seid, List<PfcpUsageReport> reports) {}

    private static final class RecordingSink implements ReportSink {
        final List<Reported> reported = new ArrayList<>();

        @Override
        public void report(long seid, List<PfcpUsageReport> reports) {
            reported.add(new Reported(seid, List.copyOf(reports)));
        }
    }

    private static LinuxUrrSpec spec(long seid, int triggers, long volumeThreshold, boolean uplink) {
        return new LinuxUrrSpec(seid, triggers, volumeThreshold, 0, uplink);
    }

    @Test
    void deltaReportingAcrossTicks() {
        LinuxUrrRegistry registry = new LinuxUrrRegistry(new HeapUrrDatastore());
        registry.create("1", spec(9L, PfcpIe.UT_PERIO, 0L, true));
        FakeSampler sampler = new FakeSampler();
        RecordingSink sink = new RecordingSink();
        UrrPoller poller = new UrrPoller(registry, sampler, 1000, sink);

        sampler.counters.put("1", 100L);
        poller.tick();
        assertEquals(1, sink.reported.size());
        assertEquals(9L, sink.reported.get(0).seid());
        PfcpUsageReport first = sink.reported.get(0).reports().get(0);
        assertEquals(1, first.urrId());
        assertEquals(100L, first.uplinkVolume(), "first sample counts beyond the zero baseline");
        assertEquals(0L, first.downlinkVolume());
        assertEquals(PfcpIe.UT_PERIO, first.usageReportTrigger());

        sampler.counters.put("1", 250L);
        poller.tick();
        assertEquals(2, sink.reported.size());
        assertEquals(150L, sink.reported.get(1).reports().get(0).uplinkVolume(),
                "second report carries only the delta");
    }

    @Test
    void volthTriggerWhenThresholdExceeded() {
        LinuxUrrRegistry registry = new LinuxUrrRegistry(new HeapUrrDatastore());
        registry.create("1", spec(9L, PfcpIe.UT_VOLTH, 1000L, true));
        FakeSampler sampler = new FakeSampler();
        RecordingSink sink = new RecordingSink();
        UrrPoller poller = new UrrPoller(registry, sampler, 1000, sink);

        sampler.counters.put("1", 1200L);
        poller.tick();

        assertEquals(1, sink.reported.size());
        PfcpUsageReport report = sink.reported.get(0).reports().get(0);
        assertEquals(PfcpIe.UT_VOLTH, report.usageReportTrigger());
        assertEquals(1200L, report.uplinkVolume());
    }

    @Test
    void noReportWhenDeltaZeroAndNoPerioDue() {
        LinuxUrrRegistry registry = new LinuxUrrRegistry(new HeapUrrDatastore());
        registry.create("1", spec(9L, PfcpIe.UT_VOLTH, 1_000_000L, true));
        FakeSampler sampler = new FakeSampler();
        RecordingSink sink = new RecordingSink();
        UrrPoller poller = new UrrPoller(registry, sampler, 1000, sink);

        sampler.counters.put("1", 100L);
        poller.tick();
        assertEquals(1, sink.reported.size(), "positive delta still reports");

        poller.tick();
        assertEquals(1, sink.reported.size(), "zero delta + no PERIO trigger -> silent");
    }

    @Test
    void reportsGroupBySeid() {
        LinuxUrrRegistry registry = new LinuxUrrRegistry(new HeapUrrDatastore());
        registry.create("1", spec(9L, PfcpIe.UT_PERIO, 0L, true));
        registry.create("2", spec(9L, PfcpIe.UT_PERIO, 0L, false));
        FakeSampler sampler = new FakeSampler();
        RecordingSink sink = new RecordingSink();
        UrrPoller poller = new UrrPoller(registry, sampler, 1000, sink);

        sampler.counters.put("1", 10L);
        sampler.counters.put("2", 20L);
        poller.tick();

        assertEquals(1, sink.reported.size(), "one Session Report per SEID");
        assertEquals(2, sink.reported.get(0).reports().size());
        assertTrue(sink.reported.get(0).reports().stream()
                .anyMatch(r -> r.downlinkVolume() == 20L), "downlink rule reports on DL volume");
    }

    @Test
    void restoredBaselineKeepsFirstPollDeltaCorrect() {
        HeapUrrDatastore datastore = new HeapUrrDatastore();
        datastore.saveBaseline("1", 1000L);
        LinuxUrrRegistry registry = new LinuxUrrRegistry(datastore);
        registry.create("1", spec(9L, PfcpIe.UT_PERIO, 0L, true));
        FakeSampler sampler = new FakeSampler();
        RecordingSink sink = new RecordingSink();
        UrrPoller poller = new UrrPoller(registry, sampler, 1000, sink);

        sampler.counters.put("1", 1300L);
        poller.tick();

        assertEquals(300L, sink.reported.get(0).reports().get(0).uplinkVolume(),
                "restart reports only bytes beyond the persisted baseline");
    }

    @Test
    void unknownSamplerUrrsAreSkipped() {
        LinuxUrrRegistry registry = new LinuxUrrRegistry(new HeapUrrDatastore());
        registry.create("1", spec(9L, PfcpIe.UT_VOLTH, 1_000_000L, true));
        FakeSampler sampler = new FakeSampler();
        RecordingSink sink = new RecordingSink();
        UrrPoller poller = new UrrPoller(registry, sampler, 1000, sink);

        sampler.counters.put("ghost", 50L);
        poller.tick();
        assertTrue(sink.reported.isEmpty(), "unregistered URR produces no report and no error");
        assertTrue(registry.readCounter("1").orElseThrow().cumulativeBytes() == 0,
                "ghost bytes never reach a registered counter");
    }

    @Test
    void startStopLifecycle() {
        LinuxUrrRegistry registry = new LinuxUrrRegistry(new HeapUrrDatastore());
        UrrPoller poller = new UrrPoller(registry, Map::of, 60_000, (seid, reports) -> {});

        assertFalse(poller.running());
        poller.start();
        assertTrue(poller.running());
        poller.start();
        assertTrue(poller.running(), "start is idempotent");
        poller.stop();
        assertFalse(poller.running());
    }
}
