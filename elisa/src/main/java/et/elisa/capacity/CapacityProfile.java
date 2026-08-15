package et.elisa.capacity;

/**
 * Capacity ladder — {@code lab} (default) / {@code node-10k} / {@code cluster-1m}.
 * {@code cluster-1m} is a profile, not a measured claim.
 */
public enum CapacityProfile {
    LAB,
    NODE_10K,
    CLUSTER_1M;

    public static CapacityProfile parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return LAB;
        }
        return switch (raw.trim().toLowerCase()) {
            case "10k", "node-10k", "node_10k" -> NODE_10K;
            case "1m", "1mil", "cluster-1m", "cluster_1m" -> CLUSTER_1M;
            default -> LAB;
        };
    }

    /** INVITE CMP dual-write to Postgres. Off on the 1M hot path (ISPN only). */
    public boolean pgSyncOnInvite() {
        return this != CLUSTER_1M;
    }
}
