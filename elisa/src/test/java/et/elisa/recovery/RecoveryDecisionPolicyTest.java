package et.elisa.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** R1 sticky-home / R2 partition / local DialogRegistry decision matrix. */
class RecoveryDecisionPolicyTest {

    @Test
    void terminatedIsNoop() {
        assertEquals(RecoveryDecision.NOOP,
                RecoveryDecisionPolicy.decide(true, "n1", "n1", true, true, true, true));
    }

    @Test
    void localRegistryPrefersLocalRespawn() {
        assertEquals(RecoveryDecision.LOCAL_RESPAWN,
                RecoveryDecisionPolicy.decide(false, "n1", "n2", true, true, false, true));
    }

    @Test
    void stickyHomeStillUsLocalRespawn() {
        assertEquals(RecoveryDecision.LOCAL_RESPAWN,
                RecoveryDecisionPolicy.decide(false, "n1", "n1", true, false, true, true));
    }

    @Test
    void homeDeadPrimaryPartitionFailover() {
        assertEquals(RecoveryDecision.FAILOVER_SPAWN,
                RecoveryDecisionPolicy.decide(false, "n2", "n1", false, false, false, true));
    }

    @Test
    void homeDeadButNotPrimaryPartitionNoop() {
        assertEquals(RecoveryDecision.NOOP,
                RecoveryDecisionPolicy.decide(false, "n2", "n1", false, false, false, false));
    }

    @Test
    void homeAliveRemoteStickyWaitNoop() {
        assertEquals(RecoveryDecision.NOOP,
                RecoveryDecisionPolicy.decide(false, "n2", "n1", true, false, false, true));
    }
}
