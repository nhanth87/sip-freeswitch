package et.elisa.cmp;

import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.SipDialogHa;
import et.elisa.persist.SfDialogCheckpoint;
import et.elisa.recovery.DialogOwnerStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.jboss.logging.Logger;

/**
 * Dual-write CMP recovery store for invite/dialog SBB state.
 * Hot path: Infinispan {@link DialogCheckpointStore}; durable: Postgres
 * {@code sf_dialog_checkpoint}. Load order: ISPN → PG (rehydrate ISPN on miss).
 *
 * <p>Optional {@link #setSipHaEnricher} merges live {@link DialogRegistry} portable
 * meta into the checkpoint on save (same SoT plane — not a second registry).
 */
@ApplicationScoped
public class DialogCmpPersistence {

    private static final Logger LOG = Logger.getLogger(DialogCmpPersistence.class);

    @Inject DialogCheckpointStore hot;
    @Inject DialogOwnerStore owners;

    /** Test / lab fallback when CDI ISPN is disabled — never used as SoT in prod. */
    private final ConcurrentMap<String, InviteCmpSnapshot> memoryFallback = new ConcurrentHashMap<>();

    private volatile boolean pgEnabled = true;
    private volatile Function<String, DialogRegistry.PortableDialogMeta> sipHaEnricher;

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

    /**
     * Bind RA export so each CMP save dual-writes portable peers/tags into the
     * checkpoint (Elisa ISPN DIST_SYNC SoT among S-CSCF nodes).
     */
    public void setSipHaEnricher(Function<String, DialogRegistry.PortableDialogMeta> enricher) {
        this.sipHaEnricher = enricher;
    }

    @Transactional
    public void save(InviteCmpSnapshot snap) {
        if (snap == null || snap.sipCallId() == null || snap.sipCallId().isBlank()) {
            return;
        }
        InviteCmpSnapshot enriched = enrichFromRa(snap);
        DialogCheckpoint cp = enriched.toCheckpoint();
        if (hot != null && hot.enabled()) {
            hot.put(cp);
        } else {
            memoryFallback.put(enriched.sipCallId(), enriched);
        }
        // R3 dual-write: record sticky ownership fence on first/live checkpoint.
        if (owners != null && owners.enabled()
                && enriched.elisaNodeId() != null
                && !DialogCheckpoint.STATE_TERMINATED.equals(enriched.dialogState())) {
            owners.putIfAbsent(enriched.sipCallId(), enriched.elisaNodeId());
        }
        if (pgEnabled) {
            persistPg(cp);
        }
        LOG.debugf("cmp save callId=%s state=%s fs=%s haEmpty=%s",
                enriched.sipCallId(), enriched.dialogState(), enriched.fsUuid(),
                enriched.effectiveSipHa().isEmpty());
    }

    private InviteCmpSnapshot enrichFromRa(InviteCmpSnapshot snap) {
        Function<String, DialogRegistry.PortableDialogMeta> enricher = sipHaEnricher;
        if (enricher == null) {
            return snap;
        }
        try {
            DialogRegistry.PortableDialogMeta portable = enricher.apply(snap.sipCallId());
            if (portable == null) {
                return snap;
            }
            SipDialogHa ha = SipDialogHa.fromPortable(portable);
            String peer = snap.peer();
            if ((peer == null || peer.isBlank())
                    && portable.peerHost() != null && portable.peerPort() > 0) {
                peer = portable.peerHost() + ":" + portable.peerPort();
            }
            InviteCmpSnapshot withHa = snap.withSipHa(ha);
            return peer != null && !peer.equals(snap.peer()) ? withHa.withPeer(peer) : withHa;
        } catch (RuntimeException ex) {
            LOG.debugf(ex, "sip HA enrich failed callId=%s", snap.sipCallId());
            return snap;
        }
    }

    @Transactional
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
