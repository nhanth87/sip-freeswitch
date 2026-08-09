package et.elisa.cmp;

import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.persist.SfDialogCheckpoint;
import et.elisa.recovery.DialogOwnerStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jboss.logging.Logger;

/**
 * Dual-write CMP recovery store for invite/dialog SBB state.
 * Hot path: Infinispan {@link DialogCheckpointStore}; durable: Postgres
 * {@code sf_dialog_checkpoint}. Load order: ISPN → PG (rehydrate ISPN on miss).
 */
@ApplicationScoped
public class DialogCmpPersistence {

    private static final Logger LOG = Logger.getLogger(DialogCmpPersistence.class);

    @Inject DialogCheckpointStore hot;
    @Inject DialogOwnerStore owners;

    /** Test / lab fallback when CDI ISPN is disabled — never used as SoT in prod. */
    private final ConcurrentMap<String, InviteCmpSnapshot> memoryFallback = new ConcurrentHashMap<>();

    private volatile boolean pgEnabled = true;

    /** Unit-test ctor — no CDI. */
    public DialogCmpPersistence() {
        this.hot = null;
        this.owners = null;
    }

    public DialogCmpPersistence(DialogCheckpointStore hot) {
        this.hot = hot;
        this.owners = null;
    }

    public DialogCmpPersistence(DialogCheckpointStore hot, DialogOwnerStore owners) {
        this.hot = hot;
        this.owners = owners;
    }

    /** Disable Panache for pure unit tests. */
    public DialogCmpPersistence withoutPg() {
        this.pgEnabled = false;
        return this;
    }

    @Transactional
    public void save(InviteCmpSnapshot snap) {
        if (snap == null || snap.sipCallId() == null || snap.sipCallId().isBlank()) {
            return;
        }
        DialogCheckpoint cp = snap.toCheckpoint();
        if (hot != null && hot.enabled()) {
            hot.put(cp);
        } else {
            memoryFallback.put(snap.sipCallId(), snap);
        }
        // R3 dual-write: record sticky ownership fence on first/live checkpoint.
        if (owners != null && owners.enabled()
                && snap.elisaNodeId() != null
                && !DialogCheckpoint.STATE_TERMINATED.equals(snap.dialogState())) {
            owners.putIfAbsent(snap.sipCallId(), snap.elisaNodeId());
        }
        if (pgEnabled) {
            persistPg(cp);
        }
        LOG.debugf("cmp save callId=%s state=%s fs=%s", snap.sipCallId(), snap.dialogState(), snap.fsUuid());
    }

    public Optional<InviteCmpSnapshot> load(String sipCallId) {
        if (sipCallId == null || sipCallId.isBlank()) {
            return Optional.empty();
        }
        if (hot != null && hot.enabled()) {
            Optional<DialogCheckpoint> hotHit = hot.get(sipCallId);
            if (hotHit.isPresent()) {
                return hotHit.map(InviteCmpSnapshot::fromCheckpoint);
            }
        } else {
            InviteCmpSnapshot mem = memoryFallback.get(sipCallId);
            if (mem != null) {
                return Optional.of(mem);
            }
        }
        if (!pgEnabled) {
            return Optional.empty();
        }
        Optional<InviteCmpSnapshot> durable = loadPg(sipCallId);
        durable.ifPresent(snap -> {
            if (hot != null && hot.enabled()) {
                hot.put(snap.toCheckpoint());
            } else {
                memoryFallback.put(sipCallId, snap);
            }
        });
        return durable;
    }

    @Transactional
    public void remove(String sipCallId) {
        if (sipCallId == null) {
            return;
        }
        if (hot != null) {
            hot.remove(sipCallId);
        }
        memoryFallback.remove(sipCallId);
        if (owners != null) {
            owners.remove(sipCallId);
        }
        if (pgEnabled) {
            markEndedPg(sipCallId);
        }
    }

    void persistPg(DialogCheckpoint cp) {
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
        row.persist();
    }

    void markEndedPg(String sipCallId) {
        SfDialogCheckpoint row = SfDialogCheckpoint.findById(sipCallId);
        if (row == null) {
            return;
        }
        row.dialogState = DialogCheckpoint.STATE_TERMINATED;
        row.updatedAt = Instant.now();
        row.endedAt = Instant.now();
        row.persist();
    }

    Optional<InviteCmpSnapshot> loadPg(String sipCallId) {
        SfDialogCheckpoint row = SfDialogCheckpoint.findById(sipCallId);
        if (row == null || row.endedAt != null) {
            return Optional.empty();
        }
        Instant started = row.startedAt != null ? row.startedAt : Instant.now();
        Instant updated = row.updatedAt != null ? row.updatedAt : started;
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
                updated.toEpochMilli()));
    }
}
