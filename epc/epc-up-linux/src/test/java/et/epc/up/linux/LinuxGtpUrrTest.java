package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LinuxGtpUrrTest {

    @Test
    void pollReportsDeltaNotCumulative() {
        HeapUrrDatastore ds = new HeapUrrDatastore();
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0", ds);
        agent.urr().create("urr-1");
        agent.urr().addBytes("urr-1", 100);
        agent.urr().addBytes("urr-1", 50);

        List<LinuxUrrReport> first = agent.urr().poll();
        assertEquals(1, first.size());
        assertEquals("urr-1", first.get(0).urrId());
        assertEquals(150, first.get(0).reportedBytes());
        assertEquals(150, first.get(0).cumulativeBytes());

        agent.urr().addBytes("urr-1", 20);
        List<LinuxUrrReport> second = agent.urr().poll();
        assertEquals(1, second.size());
        assertEquals(20, second.get(0).reportedBytes());
        assertEquals(170, second.get(0).cumulativeBytes());
    }

    @Test
    void readCounterDoesNotAdvanceBaseline() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0", new HeapUrrDatastore());
        agent.urr().create("urr-1");
        agent.urr().addBytes("urr-1", 42);

        Optional<LinuxUrrUsage> read = agent.readCounter("urr-1");
        assertTrue(read.isPresent());
        assertEquals(42, read.get().cumulativeBytes());
        assertEquals(0, read.get().baselineBytes());
        assertEquals(42, read.get().deltaBytes());

        List<LinuxUrrReport> reports = agent.urr().poll();
        assertEquals(42, reports.get(0).reportedBytes());
    }

    @Test
    void baselineRestoredFromDatastore() {
        HeapUrrDatastore ds = new HeapUrrDatastore();
        ds.saveBaseline("urr-1", 1000);
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0", ds);
        agent.urr().create("urr-1");
        agent.urr().addBytes("urr-1", 100);

        List<LinuxUrrReport> reports = agent.urr().poll();
        assertEquals(100, reports.get(0).reportedBytes());
        assertEquals(1100, reports.get(0).cumulativeBytes());
    }

    @Test
    void pollPersistsAndReadBackIsAvailable() {
        HeapUrrDatastore ds = new HeapUrrDatastore();
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0", ds);
        agent.urr().create("urr-1");
        agent.urr().addBytes("urr-1", 500);
        agent.urr().poll();

        assertEquals(Optional.of(500L), ds.loadBaseline("urr-1"));
    }

    @Test
    void unknownUrrRejected() {
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        assertThrows(IllegalArgumentException.class, () -> agent.urr().addBytes("missing", 1));
        assertTrue(agent.readCounter("missing").isEmpty());
    }

    @Test
    void removeDeletesCounterAndDatastoreEntry() {
        HeapUrrDatastore ds = new HeapUrrDatastore();
        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0", ds);
        agent.urr().create("urr-1");
        agent.urr().addBytes("urr-1", 1);
        agent.urr().poll();

        agent.urr().remove("urr-1");
        assertEquals(0, agent.urr().size());
        assertTrue(ds.loadBaseline("urr-1").isEmpty());
    }
}