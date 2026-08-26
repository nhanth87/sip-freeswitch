package et.elisa.cmp;

import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.SipDialogHa;
import et.elisa.recovery.DialogOwnerStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Dual-write CMP recovery store for invite/dialog SBB state.
 * Hot path: Infinispan {@link DialogCheckpointStore} only (no JTA).
 * Durable Postgres via {@link DialogCmpDurableWriter} when {@code sf.cmp.pg-sync=true}.
 * Load order: ISPN → PG (rehydrate ISPN on miss).
 *
 * <p>Optional {@link #setSipHaEnricher} merges live {@link DialogRegistry} portable
 * meta into the checkpoint on save (same SoT plane — not a second registry).
 */
@ApplicationScoped
public class DialogCmpPersistence {

    private static final Logger LOG = LogManager.getLogger(DialogCmpPersistence.class);

    @Inject DialogCheckpointStore hot;
    @Inject DialogOwnerStore owners;
    @Inject DialogCmpDurableWriter durable;

    /** Test / lab fallback when CDI ISPN is disabled — never used as SoT in prod. */
    private final ConcurrentMap<String, InviteCmpSnapshot> memoryFallback = new ConcurrentHashMap<>();

    private volatile boolean pgEnabled = true;
    private volatile boolean pgSync = true;
    private volatile Function<String, DialogRegistry.PortableDialogMeta> sipHaEnricher;

    /** Unit-test ctor — no CDI. */
    public DialogCmpPersistence() {
        this.hot = null;
        this.owners = null;
        this.durable = null;
    }

    public DialogCmpPersistence(DialogCheckpointStore hot) {
        this.hot = hot;
        this.owners = null;
        this.durable = null;
    }

    public DialogCmpPersistence(DialogCheckpointStore hot, DialogOwnerStore owners) {
        this.hot = hot;
        this.owners = owners;
        this.durable = null;
    }

    /** Disable Panache for pure unit tests. */
    public DialogCmpPersistence withoutPg() {
        this.pgEnabled = false;
        return this;
    }

    /** ISPN-only INVITE path (cluster-1m). Load/save skip Postgres. */
    public DialogCmpPersistence withoutPgSync() {
        this.pgSync = false;
        return this;
    }

    @Inject
    void applyPgSync(@ConfigProperty(name = "sf.cmp.pg-sync", defaultValue = "true") boolean pgSync) {
        this.pgSync = pgSync;
    }

    /**
     * Bind RA export so each CMP save dual-writes portable peers/tags into the
     * checkpoint (Elisa ISPN DIST_SYNC SoT among S-CSCF nodes).
     */
    public void setSipHaEnricher(Function<String, DialogRegistry.PortableDialogMeta> enricher) {
        this.sipHaEnricher = enricher;
    }

    public void save(InviteCmpSnapshot snap) {
        if (snap == null || snap.sipCallId() == null || snap.sipCallId().isBlank()) {
            return;
        }
        InviteCmpSnapshot enriched = enrichFromRa(snap);
        boolean ended = DialogCheckpoint.STATE_TERMINATED.equals(enriched.dialogState());
        if (!ended) {
            enriched = mergeMonotonicHa(enriched);
        }
        DialogCheckpoint cp = enriched.toCheckpoint();
        if (hot != null && hot.enabled()) {
            if (ended) {
                hot.remove(enriched.sipCallId());
            } else {
                hot.put(cp);
            }
        } else if (ended) {
            memoryFallback.remove(enriched.sipCallId());
        } else {
            memoryFallback.put(enriched.sipCallId(), enriched);
        }
        // R3 dual-write: record sticky ownership fence on first/live checkpoint.
        if (owners != null && owners.enabled()
                && enriched.elisaNodeId() != null
                && !DialogCheckpoint.STATE_TERMINATED.equals(enriched.dialogState())) {
            owners.putIfAbsent(enriched.sipCallId(), enriched.elisaNodeId());
        }
        if (usePg()) {
            durable.persist(cp);
        }
        LOG.debug("cmp save callId=%s state=%s fs=%s haEmpty=%s",
                enriched.sipCallId(), enriched.dialogState(), enriched.fsUuid(),
                enriched.effectiveSipHa().isEmpty());
    }

    /**
     * W1-a monotonic HA: a later save with partial/empty RA export merges over
     * the previously stored checkpoint instead of replacing it — tags/contacts
     * already checkpointed are never lost mid-dialog.
     */
    private InviteCmpSnapshot mergeMonotonicHa(InviteCmpSnapshot enriched) {
        return storedSnapshot(enriched.sipCallId())
                .map(prev -> {
                    SipDialogHa existing = enriched.effectiveSipHa();
                    SipDialogHa merged = prev.effectiveSipHa().merge(existing);
                    // merge returns `existing` by reference when prev contributes nothing.
                    InviteCmpSnapshot out = merged == existing
                            ? enriched : enriched.withSipHa(merged);
                    String peer = isBlank(enriched.peer()) && !isBlank(prev.peer())
                            ? prev.peer() : enriched.peer();
                    return peer != null && !peer.equals(enriched.peer())
                            ? out.withPeer(peer) : out;
                })
                .orElse(enriched);
    }

    private java.util.Optional<InviteCmpSnapshot> storedSnapshot(String sipCallId) {
        if (hot != null && hot.enabled()) {
            return hot.get(sipCallId).map(InviteCmpSnapshot::fromCheckpoint);
        }
        return java.util.Optional.ofNullable(memoryFallback.get(sipCallId));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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
            LOG.debug("sip HA enrich failed callId=%s", snap.sipCallId(), ex);
            return snap;
        }
    }

    public Optional<InviteCmpSnapshot> load(String sipCallId) {
        if (sipCallId == null || sipCallId.isBlank()) {
            return Optional.empty();
        }
        String canon = SipCallIds.canonical(sipCallId);
        Optional<InviteCmpSnapshot> hit = loadExact(canon);
        if (hit.isPresent()) {
            return hit;
        }
        if (!canon.equals(sipCallId)) {
            return loadExact(sipCallId);
        }
        return Optional.empty();
    }

    private Optional<InviteCmpSnapshot> loadExact(String sipCallId) {
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
        if (!usePg()) {
            return Optional.empty();
        }
        Optional<InviteCmpSnapshot> found = durable.load(sipCallId);
        found.ifPresent(snap -> {
            if (DialogCheckpoint.STATE_TERMINATED.equals(snap.dialogState())) {
                return;
            }
            if (hot != null && hot.enabled()) {
                hot.put(snap.toCheckpoint());
            } else {
                memoryFallback.put(snap.sipCallId(), snap);
            }
        });
        return found;
    }

    public void remove(String sipCallId) {
        if (sipCallId == null) {
            return;
        }
        String canon = SipCallIds.canonical(sipCallId);
        removeExact(canon);
        if (!canon.equals(sipCallId)) {
            removeExact(sipCallId);
        }
    }

    private void removeExact(String sipCallId) {
        if (hot != null) {
            hot.remove(sipCallId);
        }
        memoryFallback.remove(sipCallId);
        if (owners != null) {
            owners.remove(sipCallId);
        }
        if (usePg()) {
            durable.markEnded(sipCallId);
        }
    }

    private boolean usePg() {
        return pgEnabled && pgSync && durable != null;
    }
}
