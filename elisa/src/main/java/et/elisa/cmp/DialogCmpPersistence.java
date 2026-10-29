package et.elisa.cmp;

import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.SipDialogHa;
import et.elisa.persist.CoalescingWriteBehind;
import et.elisa.recovery.DialogOwnerStore;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Dual-write CMP recovery store for invite/dialog SBB state.
 * Hot path: Infinispan {@link DialogCheckpointStore} only (no JTA).
 * Durable Postgres via {@link DialogCmpDurableWriter} when {@code sf.cmp.pg-sync=true},
 * written behind the SBB thread ({@link CoalescingWriteBehind}, {@code sf.cmp.pg-write-behind-ms};
 * 0 = synchronous). Load order: ISPN → queued PG op → PG (rehydrate ISPN on miss).
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
    private volatile long writeBehindMs = 50L;
    private volatile CoalescingWriteBehind<PgOp> writeBehind;

    /** Queued PG op: {@code cp == null} marks the dialog ended. */
    record PgOp(String callId, DialogCheckpoint cp) {}

    /**
     * Call-IDs ended recently (≈ SIP Timer J / 64·T1). A retransmitted BYE or the far
     * end's late response must not turn into a PG SELECT per message under load.
     */
    private static final long TOMBSTONE_NANOS = TimeUnit.SECONDS.toNanos(32);
    private static final int TOMBSTONE_MAX = 200_000;
    private final ConcurrentMap<String, Long> recentlyEnded = new ConcurrentHashMap<>();
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

    /** Unit-test ctor with a durable writer (fake PG). */
    DialogCmpPersistence(DialogCheckpointStore hot, DialogCmpDurableWriter durable, long writeBehindMs) {
        this.hot = hot;
        this.owners = null;
        this.durable = durable;
        this.writeBehindMs = writeBehindMs;
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

    @Inject
    void applyWriteBehind(@ConfigProperty(name = "sf.cmp.pg-write-behind-ms", defaultValue = "50") long ms) {
        this.writeBehindMs = ms;
    }

    @PreDestroy
    void shutdown() {
        CoalescingWriteBehind<PgOp> wb = writeBehind;
        if (wb != null) {
            wb.stop();
        }
    }

    /** Queued PG ops not yet drained (0 when synchronous / PG off). */
    public int pendingPgWrites() {
        CoalescingWriteBehind<PgOp> wb = writeBehind;
        return wb == null ? 0 : wb.pendingCount();
    }

    /** Drain queued PG ops now (tests / admin). */
    public void flushPg() {
        CoalescingWriteBehind<PgOp> wb = writeBehind;
        if (wb != null) {
            wb.flush();
        }
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
            CoalescingWriteBehind<PgOp> wb = writeBehind();
            if (wb != null) {
                wb.put(cp.callId(), new PgOp(cp.callId(), cp));
            } else {
                durable.persist(cp);
            }
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
        if (recentlyEnded(sipCallId)) {
            return Optional.empty();
        }
        CoalescingWriteBehind<PgOp> wb = writeBehind;
        PgOp queued = wb == null ? null : wb.pendingFor(sipCallId);
        Optional<InviteCmpSnapshot> found;
        if (queued != null && queued.cp() == null) {
            return Optional.empty();
        } else if (queued != null) {
            found = Optional.of(InviteCmpSnapshot.fromCheckpoint(queued.cp()));
        } else {
            found = durable.load(sipCallId);
        }
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
            markRecentlyEnded(sipCallId);
            CoalescingWriteBehind<PgOp> wb = writeBehind();
            if (wb != null) {
                wb.put(sipCallId, new PgOp(sipCallId, null));
            } else {
                durable.markEnded(sipCallId);
            }
        }
    }

    /**
     * ISPN / memory only — no PG fallback. For an initial INVITE (no state can exist
     * yet, only a re-INVITE can hit), so a fresh Call-ID never costs a PG SELECT.
     */
    public Optional<InviteCmpSnapshot> loadHot(String sipCallId) {
        if (sipCallId == null || sipCallId.isBlank()) {
            return Optional.empty();
        }
        String canon = SipCallIds.canonical(sipCallId);
        Optional<InviteCmpSnapshot> hit = storedSnapshot(canon);
        return hit.isPresent() || canon.equals(sipCallId) ? hit : storedSnapshot(sipCallId);
    }

    private CoalescingWriteBehind<PgOp> writeBehind() {
        CoalescingWriteBehind<PgOp> wb = writeBehind;
        if (wb != null || writeBehindMs <= 0) {
            return wb;
        }
        synchronized (this) {
            if (writeBehind == null) {
                DialogCmpDurableWriter writer = durable;
                writeBehind = new CoalescingWriteBehind<>("cmp", batch -> {
                    List<DialogCheckpoint> upserts = new ArrayList<>();
                    List<String> ended = new ArrayList<>();
                    for (PgOp op : batch) {
                        if (op.cp() == null) {
                            ended.add(op.callId());
                        } else {
                            upserts.add(op.cp());
                        }
                    }
                    writer.writeBatch(upserts, ended);
                }, writeBehindMs);
            }
            return writeBehind;
        }
    }

    private void markRecentlyEnded(String sipCallId) {
        if (recentlyEnded.size() >= TOMBSTONE_MAX) {
            long now = System.nanoTime();
            recentlyEnded.values().removeIf(at -> now - at > TOMBSTONE_NANOS);
            if (recentlyEnded.size() >= TOMBSTONE_MAX) {
                recentlyEnded.clear();
            }
        }
        recentlyEnded.put(sipCallId, System.nanoTime());
    }

    private boolean recentlyEnded(String sipCallId) {
        Long at = recentlyEnded.get(sipCallId);
        if (at == null) {
            return false;
        }
        if (System.nanoTime() - at > TOMBSTONE_NANOS) {
            recentlyEnded.remove(sipCallId, at);
            return false;
        }
        return true;
    }

    private boolean usePg() {
        return pgEnabled && pgSync && durable != null;
    }
}
