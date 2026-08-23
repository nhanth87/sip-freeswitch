package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QosProfileTest {

    @Test
    void pilotDefaultIsQci9NonPreemptCapable() {
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
    void fullConstructorKeepsValues() {
        QosProfile q = new QosProfile(1, 2, true, false, 10, 20, 30, 40);
        assertEquals(1, q.qci());
        assertEquals(2, q.arpPriority());
        assertTrue(q.preemptionCapability());
        assertFalse(q.preemptionVulnerability());
        assertEquals(10, q.apnAmbrUlBps());
        assertEquals(20, q.apnAmbrDlBps());
        assertEquals(30, q.ueAmbrUlBps());
        assertEquals(40, q.ueAmbrDlBps());
    }
}