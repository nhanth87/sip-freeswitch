package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoneyReadyBulkTest {

    @Test
    void downIsNotReadyWithReason() {
        MoneyReady m = MoneyReady.down("no-cea");
        assertFalse(m.peerReady());
        assertEquals("no-cea", m.reason());
    }

    @Test
    void upIsReadyWithPeerEvidence() {
        MoneyReady m = MoneyReady.up();
        assertTrue(m.peerReady());
        assertEquals("peer-ready", m.reason());
    }

    @Test
    void recordAccessors() {
        MoneyReady m = new MoneyReady(true, "cea");
        assertTrue(m.peerReady());
        assertEquals("cea", m.reason());
    }
}