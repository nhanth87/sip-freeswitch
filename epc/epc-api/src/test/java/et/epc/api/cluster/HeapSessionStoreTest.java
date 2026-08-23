package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HeapSessionStoreTest {

    private static SessionCheckpoint cp(String id, String role, int s11cTeid) {
        return new SessionCheckpoint(id, role, "001010000000001", "internet", 5,
                "epc-1", 1L, "", null, null, null, 1L, "1", s11cTeid, 1L);
    }

    @Test
    void putGetRemoveSize() {
        HeapSessionStore s = new HeapSessionStore();
        s.put(cp("sgw|1", "sgw", 10));
        assertEquals(1, s.size());
        assertTrue(s.get("sgw|1").isPresent());
        s.put(cp("sgw|1", "sgw", 10)); // overwrite
        assertEquals(1, s.size());
        s.remove("sgw|1");
        assertEquals(0, s.size());
        assertTrue(s.get("sgw|1").isEmpty());
    }

    @Test
    void getUnknownReturnsEmpty() {
        HeapSessionStore s = new HeapSessionStore();
        assertTrue(s.get("missing").isEmpty());
    }

    @Test
    void findByS11cTeidOnlyMatchesSgwRole() {
        HeapSessionStore s = new HeapSessionStore();
        s.put(cp("sgw|1", "sgw", 10));
        s.put(cp("pgw|2", "pgw", 20));
        assertTrue(s.findByS11cTeid(10).isPresent());
        assertFalse(s.findByS11cTeid(20).isPresent());
        assertFalse(s.findByS11cTeid(999).isPresent());
    }
}