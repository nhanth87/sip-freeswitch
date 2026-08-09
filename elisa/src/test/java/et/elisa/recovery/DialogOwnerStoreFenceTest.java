package et.elisa.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** R3 fencing CAS — dual-spawn prevention. */
class DialogOwnerStoreFenceTest {

    private DialogOwnerStore store;

    @BeforeEach
    void setUp() {
        store = new DialogOwnerStore().memoryOnly("elisa-1");
    }

    @Test
    void putIfAbsentWinsOnce() {
        assertTrue(store.putIfAbsent("cid-1", "elisa-1"));
        assertFalse(store.putIfAbsent("cid-1", "elisa-2"));
        assertEquals("elisa-1", store.get("cid-1").orElseThrow().ownerNodeId());
        assertEquals(0L, store.get("cid-1").orElseThrow().generation());
    }

    @Test
    void claimBumpsGenerationLoserNoops() {
        assertTrue(store.putIfAbsent("cid-2", "elisa-1"));
        DialogOwner expected = store.get("cid-2").orElseThrow();
        assertTrue(store.tryClaim(expected, "elisa-2"));
        assertEquals(1L, store.get("cid-2").orElseThrow().generation());
        assertEquals("elisa-2", store.get("cid-2").orElseThrow().ownerNodeId());

        // Stale expected (gen 0) must lose.
        assertFalse(store.tryClaim(expected, "elisa-3"));
        assertEquals("elisa-2", store.get("cid-2").orElseThrow().ownerNodeId());
        assertEquals(1L, store.get("cid-2").orElseThrow().generation());
    }

    @Test
    void tryClaimByExpectedGeneration() {
        assertTrue(store.putIfAbsent("cid-3", "dead-node"));
        assertTrue(store.tryClaim("cid-3", "elisa-1", 0L));
        assertFalse(store.tryClaim("cid-3", "elisa-2", 0L)); // stale gen
        assertTrue(store.tryClaim("cid-3", "elisa-2", 1L));
        assertEquals(2L, store.get("cid-3").orElseThrow().generation());
    }

    @Test
    void listOwnedByFilters() {
        store.putIfAbsent("a", "n1");
        store.putIfAbsent("b", "n2");
        store.putIfAbsent("c", "n1");
        assertEquals(2, store.listOwnedBy("n1").size());
        assertEquals(1, store.listOwnedBy("n2").size());
    }
}
