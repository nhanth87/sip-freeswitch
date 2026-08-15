package et.elisa.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CapacityShardsTest {

    @Test
    void blankOrSingleShardIsZero() {
        assertEquals(0, CapacityShards.of("sip:alice@ims.lab", 1));
        assertEquals(0, CapacityShards.of(null, 64));
        assertEquals(0, CapacityShards.of("  ", 64));
    }

    @Test
    void sameImpuSticksToSameShard() {
        String impu = "sip:bob@ims.lab";
        assertEquals(CapacityShards.of(impu, 32), CapacityShards.of(impu, 32));
        assertTrue(CapacityShards.of(impu, 32) >= 0);
        assertTrue(CapacityShards.of(impu, 32) < 32);
    }

    @Test
    @Timeout(10)
    void hundredThousandImpusSpreadAcrossShards() {
        int shards = 64;
        int n = 100_000;
        int[] counts = new int[shards];
        for (int i = 0; i < n; i++) {
            counts[CapacityShards.of("sip:u" + i + "@ims.lab", shards)]++;
        }
        int min = n;
        int max = 0;
        for (int c : counts) {
            min = Math.min(min, c);
            max = Math.max(max, c);
            assertTrue(c > 0, "empty shard");
        }
        double mean = n / (double) shards;
        assertTrue(max - min < 0.25 * mean, "skew min=" + min + " max=" + max + " mean=" + mean);
    }

    @Test
    void cluster1mProfileDisablesPgSync() {
        assertEquals(CapacityProfile.CLUSTER_1M, CapacityProfile.parse("1m"));
        assertEquals(CapacityProfile.CLUSTER_1M, CapacityProfile.parse("cluster-1m"));
        assertFalse(CapacityProfile.CLUSTER_1M.pgSyncOnInvite());
        assertTrue(CapacityProfile.LAB.pgSyncOnInvite());
        assertEquals(CapacityProfile.NODE_10K, CapacityProfile.parse("10k"));
        assertEquals(CapacityProfile.LAB, CapacityProfile.parse("nope"));
    }
}
