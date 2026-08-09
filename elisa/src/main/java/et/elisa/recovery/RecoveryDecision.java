package et.elisa.recovery;

/** Outcome of sticky-home / fence decision (R1–R3). */
public enum RecoveryDecision {
    LOCAL_RESPAWN,
    FAILOVER_SPAWN,
    NOOP
}
