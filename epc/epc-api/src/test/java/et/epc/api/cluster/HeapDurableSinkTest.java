package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeapDurableSinkTest {

    private static SessionCheckpoint cp(String id, String owner, String role, int s11cTeid) {
        return new SessionCheckpoint(id, role, "001010000000001", "internet", 5,
                owner, 1L, "", null, null, null, 1L, "1", s11cTeid, 1L);
    }

    @Test
    void checkpointLoadForget() {
        HeapDurableSink s = new HeapDurableSink();
        s.checkpoint(cp("a", "n1", "sgw", 10));
        assertTrue(s.load("a").isPresent());
        s.forget("a");
        assertTrue(s.load("a").isEmpty());
    }

    @Test
    void loadUnknownReturnsEmpty() {
        HeapDurableSink s = new HeapDurableSink();
        assertTrue(s.load("nope").isEmpty());
    }

    @Test
    void loadByOwnerFilters() {
        HeapDurableSink s = new HeapDurableSink();
        s.checkpoint(cp("a", "n1", "sgw", 1));
        s.checkpoint(cp("b", "n1", "sgw", 2));
        s.checkpoint(cp("c", "n2", "sgw", 3));
        List<SessionCheckpoint> owned = s.loadByOwner("n1");
        assertEquals(2, owned.size());
        List<SessionCheckpoint> none = s.loadByOwner("n9");
        assertTrue(none.isEmpty());
    }

    @Test
    void findByS11cTeidMatchesSgwRole() {
        HeapDurableSink s = new HeapDurableSink();
        s.checkpoint(cp("a", "n1", "sgw", 10));
        s.checkpoint(cp("b", "n1", "pgw", 20));
        assertTrue(s.findByS11cTeid(10).isPresent());
        assertFalse(s.findByS11cTeid(20).isPresent());
        assertFalse(s.findByS11cTeid(99).isPresent());
    }
}