package et.elisa.recovery;

/**
 * Elisa orchestrator: detect dead SBB → decide LOCAL vs failover → restore → spawn.
 * Does not replace RA SIP HA (R4); uses fencing token (R3) for dual-spawn prevention.
 */
public interface SbbRecoveryManager {

    /** D1–D3: same JVM. Returns true if entity ready for delivery. */
    boolean respawnLocal(RecoveryKey key, RecoveryCause cause);

    /** D4–D5: after fence claim. Only on winning node. */
    boolean failoverSpawn(RecoveryKey key, long expectedGeneration);

    /** Watchdog / membership: scan orphaned CONFIRMED dialogs with dead owner. */
    void onClusterViewChange(ClusterView view);

    /** Optional soft-hang: entity heartbeat miss (P2). */
    void onEntitySuspect(String sbbId, long lastBeatEpochMs);

    /** Metrics — local respawn success. */
    long localOk();

    long localFail();

    long failoverClaimWon();

    long failoverClaimLost();

    /** Failover spawn OK but SIP Send* still blocked (honest). */
    long blockedSipHa();
}
