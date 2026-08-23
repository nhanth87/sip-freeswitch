package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QosProfileBulkTest {

    @Test
    void pilotDefaultFieldValues() {
        QosProfile q = QosProfile.pilotDefault();
        assertEquals(9, q.qci());
        assertEquals(8, q.arpPriority());
        assertFalse(q.preemptionCapability());
        assertTrue(q.preemptionVulnerability());
        assertEquals(1_000_000_000L, q.apnAmbrUlBps());
        assertEquals(1_000_000_000L, q.apnAmbrDlBps());
        assertEquals(1_000_000_000L, q.ueAmbrUlBps());
        assertEquals(1_000_000_000L, q.ueAmbrDlBps());
    }

    @Test
    void recordAccessors() {
        QosProfile q = new QosProfile(5, 1, true, false, 1L, 2L, 3L, 4L);
        assertEquals(5, q.qci());
        assertEquals(1, q.arpPriority());
        assertTrue(q.preemptionCapability());
        assertEquals(2L, q.apnAmbrDlBps());
        assertEquals(4L, q.ueAmbrDlBps());
    }
}