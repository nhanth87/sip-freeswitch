package et.elisa.recovery;

/** Why recovery was requested (maps to design D1–D6). */
public enum RecoveryCause {
    EXCEPTION_ROLLBACK,
    UNEXPECTED_REMOVE,
    MISSING_ENTITY,
    NODE_LEFT,
    OWNERSHIP_LEASE_EXPIRED,
    ENTITY_SUSPECT,
    OPERATOR
}
