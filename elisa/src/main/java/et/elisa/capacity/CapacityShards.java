package et.elisa.capacity;

/**
 * Cluster-scale IMPU affinity. Hash is stable for a given key; not a load claim.
 */
public final class CapacityShards {

    private CapacityShards() {}

    /**
     * Shard index in {@code [0, shards)}. {@code shards <= 1} or blank key → {@code 0}.
     */
    public static int of(String key, int shards) {
        if (shards <= 1) {
            return 0;
        }
        if (key == null || key.isBlank()) {
            return 0;
        }
        return Math.floorMod(mix32(key.hashCode()), shards);
    }

    /** Spread sequential String.hashCode (e.g. sip:u0, sip:u1, …) across shards. */
    static int mix32(int h) {
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }
}
