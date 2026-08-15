package et.epc.api.cluster;

import java.util.Objects;

/**
 * EPC clustering is a separate Infinispan view from Elisa ({@code epc-cluster}).
 * Node id must be unique among SGW-C/PGW-C peers sharing the same cluster name.
 */
public record EpcClusterConfig(String nodeId, ClusterMode mode, String clusterName, int nodeIndex) {
    public EpcClusterConfig {
        Objects.requireNonNull(nodeId);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(clusterName);
        if (nodeIndex < 0 || nodeIndex > 255) {
            throw new IllegalArgumentException("nodeIndex 0–255");
        }
        if ("elisa-cluster".equals(clusterName)) {
            throw new IllegalArgumentException("EPC must not join elisa-cluster");
        }
    }

    public static EpcClusterConfig local(String nodeId) {
        return new EpcClusterConfig(nodeId, ClusterMode.LOCAL, "epc-cluster", 1);
    }
}
