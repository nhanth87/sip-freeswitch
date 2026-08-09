package et.elisa.recovery;

/**
 * Pure decision policy for local vs sticky-home failover (R1 / R6).
 * Extracted for unit tests without CDI / ISPN.
 */
public final class RecoveryDecisionPolicy {

    private RecoveryDecisionPolicy() {
    }

    /**
     * @param dialogTerminated     checkpoint missing or TERMINATED
     * @param thisNodeId           local elisa node id
     * @param homeNodeId           sticky home (checkpoint / registration); may be null
     * @param homeAlive            home present in primary membership (true if unknown/single-node)
     * @param dialogRegistryLocal  RA DialogRegistry still has Call-ID on this JVM
     * @param localOwner           RaHaSupport / DialogOwner says we own the dialog
     * @param primaryPartition     R2 — may claim only in primary partition
     */
    public static RecoveryDecision decide(
            boolean dialogTerminated,
            String thisNodeId,
            String homeNodeId,
            boolean homeAlive,
            boolean dialogRegistryLocal,
            boolean localOwner,
            boolean primaryPartition) {
        if (dialogTerminated) {
            return RecoveryDecision.NOOP;
        }
        // Cheapest path: same JVM still has SIP peers.
        if (dialogRegistryLocal) {
            return RecoveryDecision.LOCAL_RESPAWN;
        }
        // Sticky home still us and we are the recorded owner.
        if (thisNodeId != null && thisNodeId.equals(homeNodeId) && localOwner) {
            return RecoveryDecision.LOCAL_RESPAWN;
        }
        if (thisNodeId != null && thisNodeId.equals(homeNodeId) && homeAlive) {
            return RecoveryDecision.LOCAL_RESPAWN;
        }
        // Steal only when home is membership-dead (R1) and we are in primary partition (R2).
        if (!homeAlive && primaryPartition) {
            return RecoveryDecision.FAILOVER_SPAWN;
        }
        // Default: wait sticky / VIP affinity — do not dual-spawn.
        return RecoveryDecision.NOOP;
    }
}
