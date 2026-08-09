package et.elisa.recovery;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Membership snapshot for orphan scan (D4/D5).
 *
 * @param localNodeId       this Elisa node
 * @param members           live node ids in the primary partition
 * @param departedNodeIds   nodes that left since previous view (may be empty)
 * @param primaryPartition  R2 — only primary partition may claim
 */
public record ClusterView(
        String localNodeId,
        Set<String> members,
        List<String> departedNodeIds,
        boolean primaryPartition
) {
    public ClusterView {
        Objects.requireNonNull(localNodeId, "localNodeId");
        members = members == null ? Set.of() : Set.copyOf(members);
        departedNodeIds = departedNodeIds == null ? List.of() : List.copyOf(departedNodeIds);
    }

    public boolean isMemberAlive(String nodeId) {
        return nodeId != null && (nodeId.equals(localNodeId) || members.contains(nodeId));
    }
}
