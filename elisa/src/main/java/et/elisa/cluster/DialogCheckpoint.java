package et.elisa.cluster;

import java.io.Serializable;
import java.time.Instant;

/**
 * Serializable SIP dialog checkpoint for Infinispan (C1) + PG durable mirror.
 * App-level CMP recovery source when an SBB entity dies. Portable
 * {@link SipDialogHa} fields dual-write RA DialogRegistry peers/tags so lab
 * mid-dialog Send* can resume after S-CSCF failover (not full Chronos).
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
        Instant startedAt,
        SipDialogHa sipHa
) implements Serializable {
    private static final long serialVersionUID = 4L;

    public static final String STATE_INVITE = "INVITE";
    public static final String STATE_EARLY = "EARLY";
    public static final String STATE_CONFIRMED = "CONFIRMED";
    public static final String STATE_TERMINATED = "TERMINATED";

    public DialogCheckpoint {
        if (sipHa == null) {
            sipHa = SipDialogHa.EMPTY;
        }
    }

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
                null, null, STATE_INVITE, updatedAt, SipDialogHa.EMPTY);
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
                fsUuid, msisdn, STATE_INVITE, updatedAt, SipDialogHa.EMPTY);
    }

    /** Compat ctor with dialog state, empty SIP HA. */
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
            String msisdn,
            String dialogState,
            Instant startedAt) {
        this(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId, updatedAt,
                fsUuid, msisdn, dialogState, startedAt, SipDialogHa.EMPTY);
    }

    public DialogCheckpoint withFs(String uuid, String msisdn) {
        Instant now = Instant.now();
        return new DialogCheckpoint(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId,
                now, uuid, msisdn, dialogState == null ? STATE_INVITE : dialogState,
                startedAt == null ? now : startedAt, sipHa);
    }

    public DialogCheckpoint withState(String state) {
        Instant now = Instant.now();
        return new DialogCheckpoint(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId,
                now, fsUuid, msisdn, state,
                startedAt == null ? now : startedAt, sipHa);
    }

    public DialogCheckpoint withSipHa(SipDialogHa ha) {
        Instant now = Instant.now();
        return new DialogCheckpoint(callId, peer, fromUri, toUri, trunkUri, fsNodeId, elisaNodeId,
                now, fsUuid, msisdn, dialogState,
                startedAt == null ? now : startedAt, ha != null ? ha : SipDialogHa.EMPTY);
    }

    public String effectiveState() {
        return dialogState == null || dialogState.isBlank() ? STATE_INVITE : dialogState;
    }

    public SipDialogHa effectiveSipHa() {
        return sipHa == null ? SipDialogHa.EMPTY : sipHa;
    }
}
