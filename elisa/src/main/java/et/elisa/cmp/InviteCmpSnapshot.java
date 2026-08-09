package et.elisa.cmp;

import et.elisa.cluster.DialogCheckpoint;
import java.time.Instant;

/**
 * Immutable snapshot of {@code ScscfInviteSbb} CMP fields for dual persist /
 * restore (ISPN {@link DialogCheckpoint} + PG {@code sf_dialog_checkpoint}).
 */
public record InviteCmpSnapshot(
        String sipCallId,
        String fsUuid,
        String msisdn,
        String fromUri,
        String toUri,
        String trunkUri,
        String peer,
        String fsNodeId,
        String elisaNodeId,
        String dialogState,
        long startedAtEpochMs,
        long updatedAtEpochMs
) {
    public static InviteCmpSnapshot fromCheckpoint(DialogCheckpoint cp) {
        if (cp == null || cp.callId() == null) {
            return null;
        }
        Instant started = cp.startedAt() != null ? cp.startedAt() : cp.updatedAt();
        Instant updated = cp.updatedAt() != null ? cp.updatedAt() : Instant.now();
        long startedMs = started != null ? started.toEpochMilli() : 0L;
        long updatedMs = updated.toEpochMilli();
        return new InviteCmpSnapshot(
                cp.callId(),
                cp.fsUuid(),
                cp.msisdn(),
                cp.fromUri(),
                cp.toUri(),
                cp.trunkUri(),
                cp.peer(),
                cp.fsNodeId(),
                cp.elisaNodeId(),
                cp.effectiveState(),
                startedMs,
                updatedMs);
    }

    public DialogCheckpoint toCheckpoint() {
        Instant started = startedAtEpochMs > 0 ? Instant.ofEpochMilli(startedAtEpochMs) : Instant.now();
        Instant updated = updatedAtEpochMs > 0 ? Instant.ofEpochMilli(updatedAtEpochMs) : Instant.now();
        return new DialogCheckpoint(
                sipCallId,
                peer,
                fromUri,
                toUri,
                trunkUri,
                fsNodeId,
                elisaNodeId,
                updated,
                fsUuid,
                msisdn,
                dialogState == null || dialogState.isBlank()
                        ? DialogCheckpoint.STATE_INVITE
                        : dialogState,
                started);
    }

    public InviteCmpSnapshot withState(String state) {
        return new InviteCmpSnapshot(
                sipCallId, fsUuid, msisdn, fromUri, toUri, trunkUri, peer,
                fsNodeId, elisaNodeId, state, startedAtEpochMs, Instant.now().toEpochMilli());
    }

    public InviteCmpSnapshot withFs(String uuid, String msisdnValue) {
        return new InviteCmpSnapshot(
                sipCallId, uuid, msisdnValue != null ? msisdnValue : msisdn,
                fromUri, toUri, trunkUri, peer,
                fsNodeId, elisaNodeId, dialogState, startedAtEpochMs, Instant.now().toEpochMilli());
    }
}
