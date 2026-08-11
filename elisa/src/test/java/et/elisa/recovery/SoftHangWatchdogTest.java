package et.elisa.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SoftHangWatchdogTest {

    private DialogOwnerStore owners;
    private DialogCmpPersistence cmp;
    private SbbRecoveryManagerImpl mgr;
    private SoftHangWatchdog watchdog;

    @BeforeEach
    void setUp() {
        owners = new DialogOwnerStore().memoryOnly("elisa-1");
        cmp = new DialogCmpPersistence(null, owners).withoutPg();
        mgr = new SbbRecoveryManagerImpl(null, cmp, null, owners, null, null, null);
        watchdog = new SoftHangWatchdog();
        watchdog.recovery = mgr;
        watchdog.owners = owners;
        watchdog.dialogCmp = cmp;
        watchdog.staleSeconds = 60;
    }

    @Test
    void staleConfirmedTriggersSuspect() {
        long now = 1_000_000L;
        cmp.save(new InviteCmpSnapshot(
                "cid-stale", "fs-1", "2519", "sip:a@x", "sip:b@x", null, "10.0.0.1:5060",
                "fs-a", "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now - 120_000L, now - 120_000L));
        assertEquals(1, watchdog.scanAt(now));
        assertEquals(1L, mgr.localOk());
    }

    @Test
    void freshConfirmedDoesNotSuspect() {
        long now = 1_000_000L;
        cmp.save(new InviteCmpSnapshot(
                "cid-fresh", "fs-1", "2519", "sip:a@x", "sip:b@x", null, "10.0.0.1:5060",
                "fs-a", "elisa-1", DialogCheckpoint.STATE_CONFIRMED, now - 5_000L, now - 5_000L));
        assertEquals(0, watchdog.scanAt(now));
        assertEquals(0L, mgr.localOk());
    }
}
