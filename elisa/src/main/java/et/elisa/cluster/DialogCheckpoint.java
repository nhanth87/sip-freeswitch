package et.elisa.cluster;

import java.io.Serializable;
import java.time.Instant;

/**
 * Serializable SIP dialog checkpoint for Infinispan (C1) + PG durable mirror.
 * App-level CMP recovery source when an SBB entity dies; RA {@code DialogRegistry}
 * remains JVM-local and is <em>not</em> reconstructed from this record alone.
 */
public record DialogCheckpoint(
        String callId,
        String peer,
        String fromUri,
        String toUri,
        String trunkUri,
        String fsNodeId,
        String elisaNodeId,
        Instant updatedAt,
        String fsUuid,
        String msisdn,
        String dialogState,
        Instant startedAt
) implements Serializable {
    private static final long serialVersionUID = 3L;

    public static final String STATE_INVITE = "INVITE";
    public static final String STATE_EARLY = "EARLY";
    public static final String STATE_CONFIRMED = "CONFIRMED";
    public static final String STATE_TERMINATED = "TERMINATED";

    /** Compat ctor without FS correlate / dialog state. */
    public DialogCheckpoint(
            String callId,
            String peer,
            String fromUri,
            String toUri,
            String trunkUri,
            String fsNodeId,
            String elisaNodeId,
            Instant updatedAt) {
        this(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId, updatedAt,
                null, null, STATE_INVITE, updatedAt);
    }

    /** Compat ctor with FS correlate, default INVITE state. */
    public DialogCheckpoint(
            String callId,
            String peer,
            String fromUri,
            String toUri,
            String trunkUri,
            String fsNodeId,
            String elisaNodeId,
            Instant updatedAt,
            String fsUuid,
            String msisdn) {
        this(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId, updatedAt,
                fsUuid, msisdn, STATE_INVITE, updatedAt);
    }

    public DialogCheckpoint withFs(String uuid, String msisdn) {
        Instant now = Instant.now();
        return new DialogCheckpoint(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId,
                now, uuid, msisdn, dialogState == null ? STATE_INVITE : dialogState,
                startedAt == null ? now : startedAt);
    }

    public DialogCheckpoint withState(String state) {
        Instant now = Instant.now();
        return new DialogCheckpoint(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId,
                now, fsUuid, msisdn, state,
                startedAt == null ? now : startedAt);
    }

    public String effectiveState() {
        return dialogState == null || dialogState.isBlank() ? STATE_INVITE : dialogState;
    }
}
