package et.elisa.cmp;

import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.SipDialogHa;
import et.elisa.persist.SfDialogCheckpoint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Postgres dual-write for dialog CMP. Skipped entirely when {@code sf.cmp.pg-sync=false}
 * (cluster-1m profile); otherwise fed off the SBB thread by {@link DialogCmpWriteBehind}
 * via {@link #writeBatch}. Own bean so {@code @Transactional} is not a no-op self-invoke
 * from {@link DialogCmpPersistence}.
 */
@ApplicationScoped
public class DialogCmpDurableWriter {

    @Transactional
    public void persist(DialogCheckpoint cp) {
        upsert(cp);
    }

    /** One transaction per write-behind drain: N upserts + M end marks. */
    @Transactional
    public void writeBatch(List<DialogCheckpoint> upserts, List<String> ended) {
        List<String> ids = new ArrayList<>(upserts.size() + ended.size());
        upserts.forEach(cp -> ids.add(cp.callId()));
        ids.addAll(ended);
        if (ids.isEmpty()) {
            return;
        }
        // One IN-select warms the persistence context; findById below then hits it
        // instead of issuing one SELECT per row.
        SfDialogCheckpoint.list("sipCallId in ?1", ids);
        for (DialogCheckpoint cp : upserts) {
            upsert(cp);
        }
        for (String callId : ended) {
            end(callId);
        }
    }

    private static void upsert(DialogCheckpoint cp) {
        SfDialogCheckpoint row = SfDialogCheckpoint.findById(cp.callId());
        if (row == null) {
            row = new SfDialogCheckpoint();
            row.sipCallId = cp.callId();
            row.startedAt = cp.startedAt() != null ? cp.startedAt() : Instant.now();
        }
        row.peer = cp.peer();
        row.fromUri = cp.fromUri();
        row.toUri = cp.toUri();
        row.trunkUri = cp.trunkUri();
        row.fsUuid = cp.fsUuid();
        row.msisdn = cp.msisdn();
        row.fsNodeId = cp.fsNodeId();
        row.elisaNodeId = cp.elisaNodeId();
        row.dialogState = cp.effectiveState();
        row.updatedAt = cp.updatedAt() != null ? cp.updatedAt() : Instant.now();
        row.endedAt = DialogCheckpoint.STATE_TERMINATED.equals(row.dialogState) ? Instant.now() : null;
        SipDialogHa ha = cp.effectiveSipHa();
        row.remotePeer = ha.remotePeer();
        row.transport = ha.transport();
        row.remoteTransport = ha.remoteTransport();
        row.cseq = ha.cseq();
        row.fromTag = ha.fromTag();
        row.toTag = ha.toTag();
        row.contactUri = ha.contactUri();
        row.remoteContactUri = ha.remoteContactUri();
        row.sipMethod = ha.method();
        row.farFromUri = ha.farFromUri();
        row.farFromTag = ha.farFromTag();
        row.persist();
    }

    @Transactional
    public void markEnded(String sipCallId) {
        end(sipCallId);
    }

    private static void end(String sipCallId) {
        SfDialogCheckpoint row = SfDialogCheckpoint.findById(sipCallId);
        if (row == null) {
            return;
        }
        row.dialogState = DialogCheckpoint.STATE_TERMINATED;
        row.updatedAt = Instant.now();
        row.endedAt = Instant.now();
        row.persist();
    }

    @Transactional
    public Optional<InviteCmpSnapshot> load(String sipCallId) {
        SfDialogCheckpoint row = SfDialogCheckpoint.findById(sipCallId);
        if (row == null || row.endedAt != null) {
            return Optional.empty();
        }
        Instant started = row.startedAt != null ? row.startedAt : Instant.now();
        Instant updated = row.updatedAt != null ? row.updatedAt : started;
        SipDialogHa ha = new SipDialogHa(
                row.remotePeer,
                row.transport,
                row.remoteTransport,
                row.cseq,
                row.fromTag,
                row.toTag,
                row.contactUri,
                row.remoteContactUri,
                row.sipMethod,
                row.farFromUri,
                row.farFromTag);
        return Optional.of(new InviteCmpSnapshot(
                row.sipCallId,
                row.fsUuid,
                row.msisdn,
                row.fromUri,
                row.toUri,
                row.trunkUri,
                row.peer,
                row.fsNodeId,
                row.elisaNodeId,
                row.dialogState,
                started.toEpochMilli(),
                updated.toEpochMilli(),
                ha));
    }
}
