package et.elisa.cluster;

import java.io.Serializable;
import java.time.Instant;

/** Serializable SIP dialog checkpoint for Infinispan (C1). */
public record DialogCheckpoint(
        String callId,
        String peer,
        String fromUri,
        String toUri,
        String trunkUri,
        String fsNodeId,
        String elisaNodeId,
        Instant updatedAt
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
