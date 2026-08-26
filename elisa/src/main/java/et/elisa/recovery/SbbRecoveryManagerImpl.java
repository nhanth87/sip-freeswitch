package et.elisa.recovery;

import com.microjainslee.api.Sbb;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;
import com.microjainslee.core.recovery.RecoverySnapshot;
import com.microjainslee.core.recovery.SessionRecoveryService;
import com.microjainslee.core.removal.EntityRemovalEvent;
import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;
import et.elisa.cluster.CallSession;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.FsPairRegistry;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import et.elisa.esl.HomeEslRemapService;
import et.elisa.ims.scscf.ScscfInviteSbb;
import et.elisa.profile.ElisaProfileService;
import et.elisa.session.CallSessionSbb;
import et.elisa.session.CallSessionService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * CDI orchestrator for dead-SBB detect → decide → restore → spawn (R1–R10).
 *
 * <p>EntityRemovalBus subscribers must stay off the container call stack —
 * removals are enqueued onto a virtual-thread executor.
 */
@ApplicationScoped
public class SbbRecoveryManagerImpl implements SbbRecoveryManager {

    private static final Logger LOG = LogManager.getLogger(SbbRecoveryManagerImpl.class);

    /** R10 — prevent nested unbounded respawns on the same thread. */
    private static final ThreadLocal<Boolean> RECOVERING =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject MicroSleeContainer container;
    @Inject DialogCmpPersistence dialogCmp;
    @Inject CallSessionService callSessions;
    @Inject DialogOwnerStore owners;
    @Inject DialogCheckpointStore checkpoints;
    @Inject FsPairRegistry fsPairRegistry;
    @Inject ElisaProfileService profiles;
    @Inject HomeEslRemapService homeEslRemap;

    private volatile SipServletResourceAdaptor sipRa;
    private volatile SessionRecoveryService sessionRecovery;
    private volatile java.util.function.Consumer<EntityRemovalEvent> removalListener;

    private final AtomicLong localOk = new AtomicLong();
    private final AtomicLong localFail = new AtomicLong();
    private final AtomicLong claimWon = new AtomicLong();
    private final AtomicLong claimLost = new AtomicLong();
    private final AtomicLong blockedSipHa = new AtomicLong();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();

    private ExecutorService async;

    /** CDI. */
    public SbbRecoveryManagerImpl() {
    }

    /** Unit-test wiring. */
    public SbbRecoveryManagerImpl(
            MicroSleeContainer container,
            DialogCmpPersistence dialogCmp,
            CallSessionService callSessions,
            DialogOwnerStore owners,
            DialogCheckpointStore checkpoints,
            FsPairRegistry fsPairRegistry,
            ElisaProfileService profiles) {
        this.container = container;
        this.dialogCmp = dialogCmp;
        this.callSessions = callSessions;
        this.owners = owners;
        this.checkpoints = checkpoints;
        this.fsPairRegistry = fsPairRegistry;
        this.profiles = profiles;
    }

    public void bindSipRa(SipServletResourceAdaptor ra) {
        this.sipRa = ra;
    }

    public void bindSessionRecovery(SessionRecoveryService service) {
        this.sessionRecovery = service;
    }

    /** Unit-test wiring for R9 ESL remap. */
    public void bindHomeEslRemap(HomeEslRemapService remap) {
        this.homeEslRemap = remap;
    }

    @PostConstruct
    void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ThreadFactory tf = Thread.ofVirtual().name("elisa-sbb-recovery-", 0).factory();
        async = Executors.newThreadPerTaskExecutor(tf);
        LOG.info("SbbRecoveryManager started node=" + localNodeId());
    }

    @PreDestroy
    void stop() {
        if (removalListener != null && container != null) {
            container.removeEntityRemovalListener(removalListener);
            removalListener = null;
        }
        if (async != null) {
            async.shutdownNow();
        }
        started.set(false);
    }

    /**
     * Wire EntityRemovalBus — called from {@code ElisaBootstrap} after container start.
     * Listener only enqueues (R8 — off INVITE hot path; also avoids container re-entry).
     */
    public void attachToContainer() {
        if (container == null) {
            return;
        }
        removalListener = event -> {
            if (event == null) {
                return;
            }
            if (event.reason() == EntityRemovalEvent.RemovalReason.SBB_SELF_REMOVE) {
                // R6 — intentional teardown; handleRemoval re-checks TERMINATED / EARLY|CONFIRMED.
            }
            if (async != null) {
                async.execute(() -> handleRemoval(event));
            } else {
                handleRemoval(event);
            }
        };
        container.addEntityRemovalListener(removalListener);
        LOG.info("SbbRecoveryManager attached to EntityRemovalBus");
    }

    void handleRemoval(EntityRemovalEvent event) {
        EntityRemovalEvent.RemovalReason reason = event.reason();
        if (reason == EntityRemovalEvent.RemovalReason.HOT_REDEPLOY
                || reason == EntityRemovalEvent.RemovalReason.OPERATOR
                || reason == EntityRemovalEvent.RemovalReason.TIMER_EXPIRED) {
            return;
        }
        String correlate = resolveCorrelate(event);
        if (correlate == null || correlate.isBlank()) {
            return;
        }
        if (reason == EntityRemovalEvent.RemovalReason.SBB_SELF_REMOVE) {
            // CMP TERMINATED write may lag sbbRemove on BYE — re-check before respawn.
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (isTerminated(correlate) || !isRecoverableDialog(correlate)) {
                return;
            }
        }
        if (isTerminated(correlate)) {
            return; // R6 — TERMINATED / BYE self-remove
        }
        RecoveryCause cause = reason == EntityRemovalEvent.RemovalReason.EXCEPTION_ROLLBACK
                ? RecoveryCause.EXCEPTION_ROLLBACK
                : RecoveryCause.UNEXPECTED_REMOVE;
        RecoveryKey key = RecoveryKey.sipCallId(correlate, homeNodeFor(correlate));
        registerGapSr1Snapshot(event.entityId(), correlate);
        respawnLocal(key, cause);
    }

    @Override
    public boolean respawnLocal(RecoveryKey key, RecoveryCause cause) {
        if (key == null || key.correlate() == null || key.correlate().isBlank()) {
            return false;
        }
        if (Boolean.TRUE.equals(RECOVERING.get())) {
            LOG.debug("R10 nested recovery blocked correlate=%s", key.correlate());
            return false;
        }
        if (!inFlight.add(key.correlate())) {
            return false;
        }
        RECOVERING.set(Boolean.TRUE);
        try {
            RecoveryDecision decision = decide(key, /*failoverCandidate*/ false);
            if (decision == RecoveryDecision.NOOP) {
                localFail.incrementAndGet();
                return false;
            }
            if (decision == RecoveryDecision.FAILOVER_SPAWN) {
                Optional<DialogOwner> owner = owners.get(key.correlate());
                long gen = owner.map(DialogOwner::generation).orElse(0L);
                return failoverSpawn(key, gen);
            }
            boolean ok = spawnAndRestore(key, /*failover*/ false);
            if (ok) {
                localOk.incrementAndGet();
            } else {
                localFail.incrementAndGet();
            }
            return ok;
        } finally {
            RECOVERING.set(Boolean.FALSE);
            inFlight.remove(key.correlate());
        }
    }

    @Override
    public boolean failoverSpawn(RecoveryKey key, long expectedGeneration) {
        if (key == null || key.correlate() == null) {
            return false;
        }
        if (Boolean.TRUE.equals(RECOVERING.get())) {
            return false;
        }
        if (!owners.isPrimaryPartition()) {
            claimLost.incrementAndGet();
            return false;
        }
        if (!inFlight.add(key.correlate())) {
            return false;
        }
        RECOVERING.set(Boolean.TRUE);
        try {
            if (isTerminated(key.correlate())) {
                return false;
            }
            boolean won = owners.tryClaim(key.correlate(), localNodeId(), expectedGeneration);
            if (!won) {
                claimLost.incrementAndGet();
                LOG.debug("fence claim lost callId=%s expectedGen=%d", key.correlate(), expectedGeneration);
                return false;
            }
            claimWon.incrementAndGet();
            remapHomeEsl(key);
            boolean ok = spawnAndRestore(key, /*failover*/ true);
            if (ok && sipRa != null && !hasRestoredWire(key.correlate())) {
                blockedSipHa.incrementAndGet();
                LOG.info("failover spawn ok callId=%s but SIP HA blocked until wire rebuild",
                        key.correlate());
            } else if (ok && sipRa == null) {
                blockedSipHa.incrementAndGet();
            }
            return ok;
        } finally {
            RECOVERING.set(Boolean.FALSE);
            inFlight.remove(key.correlate());
        }
    }

    @Override
    public void onClusterViewChange(ClusterView view) {
        if (view == null || !view.primaryPartition()) {
            return; // R2
        }
        for (String departed : view.departedNodeIds()) {
            if (departed == null || departed.isBlank() || departed.equals(view.localNodeId())) {
                continue;
            }
            List<DialogOwner> orphans = owners.listOwnedBy(departed);
            for (DialogOwner orphan : orphans) {
                if (isTerminated(orphan.callId())) {
                    continue;
                }
                RecoveryKey key = RecoveryKey.sipCallId(orphan.callId(), departed);
                if (async != null) {
                    async.execute(() -> failoverSpawn(key, orphan.generation()));
                } else {
                    failoverSpawn(key, orphan.generation());
                }
            }
            // Also scan checkpoints whose elisaNodeId matches departed (owner cache miss).
            if (checkpoints != null && checkpoints.enabled()) {
                for (DialogCheckpoint cp : checkpoints.entries()) {
                    if (cp == null || !departed.equals(cp.elisaNodeId())) {
                        continue;
                    }
                    if (DialogCheckpoint.STATE_TERMINATED.equals(cp.effectiveState())) {
                        continue;
                    }
                    if (owners.get(cp.callId()).isPresent()) {
                        continue; // already handled via owner list
                    }
                    RecoveryKey key = RecoveryKey.sipCallId(cp.callId(), departed);
                    if (async != null) {
                        async.execute(() -> failoverSpawn(key, 0L));
                    } else {
                        failoverSpawn(key, 0L);
                    }
                }
            }
        }
    }

    @Override
    public void onEntitySuspect(String sbbId, long lastBeatEpochMs) {
        if (sbbId == null) {
            return;
        }
        // P2 soft-hang: treat as unexpected remove of the correlate suffix.
        String correlate = sbbId.contains("/") ? sbbId.substring(sbbId.lastIndexOf('/') + 1) : sbbId;
        respawnLocal(RecoveryKey.sipCallId(correlate), RecoveryCause.ENTITY_SUSPECT);
    }

    /** Pure decision for tests / callers. */
    public RecoveryDecision decide(RecoveryKey key, boolean failoverCandidate) {
        String correlate = key.correlate();
        if (isTerminated(correlate)) {
            return RecoveryDecision.NOOP;
        }
        String home = key.homeNodeId() != null ? key.homeNodeId() : homeNodeFor(correlate);
        boolean registryLocal = sipRa != null && sipRa.hasDialog(correlate);
        boolean localOwner = owners.get(correlate)
                .map(o -> localNodeId().equals(o.ownerNodeId()))
                .orElse(localNodeId().equals(home));
        boolean homeAlive = home == null || home.isBlank() || home.equals(localNodeId());
        // When home is another node we do not know membership unless failoverCandidate
        // supplies a ClusterView — for local path assume home alive if not us.
        if (home != null && !home.equals(localNodeId()) && !failoverCandidate) {
            homeAlive = true; // sticky wait
        }
        if (failoverCandidate && home != null && !home.equals(localNodeId())) {
            homeAlive = false;
        }
        return RecoveryDecisionPolicy.decide(
                false,
                localNodeId(),
                home,
                homeAlive,
                registryLocal,
                localOwner,
                owners.isPrimaryPartition());
    }

    boolean spawnAndRestore(RecoveryKey key, boolean failover) {
        String correlate = key.correlate();
        if (key.kind() == CorrelateKind.FS_UUID) {
            return spawnCallSession(correlate);
        }
        return spawnInvite(correlate, failover);
    }

    /**
     * R9: recovered dialog keeps media on home FS. Rebind ESL when this
     * S-CSCF's primary is a different node. Home down → eslLive=false.
     */
    void remapHomeEsl(RecoveryKey key) {
        if (homeEslRemap == null || key == null) {
            return;
        }
        HomeFs home = resolveHomeFs(key);
        if (home.fsNodeId() == null) {
            return;
        }
        homeEslRemap.remapToHomeFs(home.elisaNodeId(), home.fsNodeId());
    }

    private HomeFs resolveHomeFs(RecoveryKey key) {
        if (key.kind() == CorrelateKind.FS_UUID && callSessions != null) {
            Optional<CallSession> session = callSessions.findByFsUuid(key.correlate());
            if (session.isPresent()) {
                CallSession s = session.get();
                return new HomeFs(s.elisaNodeId(), s.fsNodeId());
            }
        }
        if (dialogCmp != null) {
            Optional<InviteCmpSnapshot> snap = dialogCmp.load(key.correlate());
            if (snap.isPresent()) {
                InviteCmpSnapshot s = snap.get();
                String fs = s.fsNodeId();
                if (fs != null && !fs.isBlank()) {
                    String elisa = s.elisaNodeId() != null ? s.elisaNodeId() : key.homeNodeId();
                    return new HomeFs(elisa, fs);
                }
            }
        }
        if (callSessions != null) {
            Optional<CallSession> session = callSessions.findBySipCallId(key.correlate());
            if (session.isPresent()) {
                CallSession s = session.get();
                return new HomeFs(s.elisaNodeId(), s.fsNodeId());
            }
        }
        return new HomeFs(key.homeNodeId(), null);
    }

    private record HomeFs(String elisaNodeId, String fsNodeId) {}

    private boolean spawnInvite(String callId, boolean failover) {
        Optional<InviteCmpSnapshot> snap = dialogCmp != null ? dialogCmp.load(callId) : Optional.empty();
        if (snap.isEmpty()) {
            LOG.debug("no CMP for callId=%s — cannot spawn", callId);
            return false;
        }
        if (DialogCheckpoint.STATE_TERMINATED.equals(snap.get().dialogState())) {
            return false;
        }
        // Prime DialogRegistry from CMP SipDialogHa before recover / Send* (peer + local).
        if (sipRa != null) {
            boolean fromCluster = sipRa.restoreDialogFromCluster(callId);
            if (!fromCluster && !hasRestoredWire(callId)) {
                restoreDialogFromCheckpoint(callId);
            }
        }
        // Ensure ownership recorded (local path).
        if (!failover && owners != null) {
            owners.putIfAbsent(callId, localNodeId());
        }
        // Warm Profile / CallSession.
        if (callSessions != null) {
            callSessions.findBySipCallId(callId).ifPresent(this::touchProfile);
        }
        if (container == null) {
            // Unit-test path without container: CMP restore only counts as success.
            return true;
        }
        String entityId = stableInviteEntityId(callId);
        try {
            SimpleSbbLocalObject existing = container.getSbbLocalObject(entityId);
            if (existing == null || existing.isRemoved()) {
                container.acquireEntity(entityId, ScscfInviteSbb.class);
                existing = container.getSbbLocalObject(entityId);
            }
            if (existing == null) {
                return false;
            }
            Sbb sbb = existing.getSbb();
            if (sbb instanceof ScscfInviteSbb invite) {
                boolean restored = invite.recoverAfterFailure(callId);
                registerGapSr1Snapshot(entityId, callId);
                return restored;
            }
            // Concrete subclass may wrap — try recover via CMP apply on $Concrete.
            if (sbb != null) {
                try {
                    var m = sbb.getClass().getMethod("recoverAfterFailure", String.class);
                    Object r = m.invoke(sbb, callId);
                    registerGapSr1Snapshot(entityId, callId);
                    return Boolean.TRUE.equals(r);
                } catch (ReflectiveOperationException ignored) {
                    return false;
                }
            }
            return false;
        } catch (RuntimeException ex) {
            LOG.warn("spawnInvite failed callId=%s", callId, ex);
            return false;
        }
    }

    private boolean spawnCallSession(String fsUuid) {
        if (callSessions == null) {
            return false;
        }
        Optional<CallSession> session = callSessions.findByFsUuid(fsUuid);
        if (session.isEmpty()) {
            return false;
        }
        touchProfile(session.get());
        if (container == null) {
            return true;
        }
        String entityId = "CallSessionSbb/" + fsUuid;
        try {
            SimpleSbbLocalObject existing = container.getSbbLocalObject(entityId);
            if (existing == null || existing.isRemoved()) {
                container.acquireEntity(entityId, CallSessionSbb.class);
                existing = container.getSbbLocalObject(entityId);
            }
            if (existing == null) {
                return false;
            }
            Sbb sbb = existing.getSbb();
            if (sbb instanceof CallSessionSbb css) {
                return css.recoverAfterFailure(fsUuid);
            }
            try {
                var m = sbb.getClass().getMethod("recoverAfterFailure", String.class);
                return Boolean.TRUE.equals(m.invoke(sbb, fsUuid));
            } catch (ReflectiveOperationException e) {
                return false;
            }
        } catch (RuntimeException ex) {
            LOG.warn("spawnCallSession failed fs=%s", fsUuid, ex);
            return false;
        }
    }

    private void touchProfile(CallSession session) {
        if (profiles == null || session == null) {
            return;
        }
        try {
            profiles.upsertCallSession(
                    session.fsUuid(), session.sipCallId(), session.msisdn(), session.tenantId(),
                    session.elisaNodeId(), session.fsNodeId(), session.direction(), session.state(),
                    session.startedAt() != null ? session.startedAt().toEpochMilli() : 0L,
                    session.updatedAt() != null ? session.updatedAt().toEpochMilli() : 0L);
        } catch (RuntimeException ex) {
            LOG.debug("profile warm failed fs=%s", session.fsUuid(), ex);
        }
    }

    private void registerGapSr1Snapshot(String entityId, String callId) {
        SessionRecoveryService svc = sessionRecovery;
        if (svc == null || entityId == null) {
            return;
        }
        Map<String, Object> cmp = new HashMap<>();
        cmp.put("sipCallId", callId);
        dialogCmp.load(callId).ifPresent(s -> {
            cmp.put("fsUuid", s.fsUuid());
            cmp.put("msisdn", s.msisdn());
            cmp.put("dialogState", s.dialogState());
            cmp.put("fromUri", s.fromUri());
            cmp.put("toUri", s.toUri());
            cmp.put("trunkUri", s.trunkUri());
            cmp.put("elisaNodeId", s.elisaNodeId());
            cmp.put("fsNodeId", s.fsNodeId());
        });
        Set<String> acis = new HashSet<>();
        acis.add(callId);
        svc.registerSnapshot(RecoverySnapshot.of(
                entityId,
                ScscfInviteSbb.class.getName(),
                cmp,
                acis,
                System.currentTimeMillis()));
    }

    private String resolveCorrelate(EntityRemovalEvent event) {
        if (event.convergenceKey() != null && !event.convergenceKey().isBlank()) {
            return event.convergenceKey();
        }
        String id = event.entityId();
        if (id == null) {
            return null;
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private boolean hasRestoredWire(String callId) {
        if (sipRa == null || callId == null || !sipRa.hasDialog(callId)) {
            return false;
        }
        var dialog = sipRa.dialogRegistry().find(callId);
        return dialog != null && dialog.lastRequest() != null;
    }

    /**
     * Lab N–N: prime DialogRegistry from Elisa DialogCheckpoint SipDialogHa
     * (same SoT as CMP — used when RaHaSupport ClusterManager is localOnly).
     */
    public boolean restoreDialogFromCheckpoint(String callId) {
        if (sipRa == null || dialogCmp == null || callId == null) {
            return false;
        }
        Optional<InviteCmpSnapshot> snap = dialogCmp.load(callId);
        if (snap.isEmpty()) {
            return false;
        }
        InviteCmpSnapshot s = snap.get();
        if (s.effectiveSipHa().isEmpty()
                && (s.peer() == null || s.peer().isBlank())
                && (s.fromUri() == null || s.fromUri().isBlank())) {
            return false;
        }
        var portable = s.effectiveSipHa().toPortable(callId, s.peer(), s.fromUri(), s.toUri());
        return sipRa.restoreDialog(portable);
    }

    private boolean isTerminated(String callId) {
        if (dialogCmp == null) {
            return true;
        }
        return dialogCmp.load(callId)
                .map(s -> DialogCheckpoint.STATE_TERMINATED.equals(s.dialogState()))
                .orElse(true); // no checkpoint → NOOP (R6 spirit)
    }

    private boolean isRecoverableDialog(String callId) {
        if (dialogCmp == null || callId == null) {
            return false;
        }
        return dialogCmp.load(callId)
                .map(s -> isRecoverableDialogState(s.dialogState()))
                .orElse(false);
    }

    /** EARLY / CONFIRMED — mid-call; not INVITE-only or TERMINATED. */
    public static boolean isRecoverableDialogState(String dialogState) {
        return DialogCheckpoint.STATE_EARLY.equals(dialogState)
                || DialogCheckpoint.STATE_CONFIRMED.equals(dialogState);
    }

    private String homeNodeFor(String callId) {
        if (dialogCmp != null) {
            Optional<InviteCmpSnapshot> snap = dialogCmp.load(callId);
            if (snap.isPresent() && snap.get().elisaNodeId() != null) {
                return snap.get().elisaNodeId();
            }
        }
        return owners.get(callId).map(DialogOwner::ownerNodeId).orElse(localNodeId());
    }

    private String localNodeId() {
        if (owners != null && owners.localNodeId() != null) {
            return owners.localNodeId();
        }
        if (fsPairRegistry != null) {
            return fsPairRegistry.nodeId();
        }
        return "elisa-1";
    }

    static String stableInviteEntityId(String callId) {
        return "ScscfInviteSbb/" + callId;
    }

    @Override
    public long localOk() {
        return localOk.get();
    }

    @Override
    public long localFail() {
        return localFail.get();
    }

    @Override
    public long failoverClaimWon() {
        return claimWon.get();
    }

    @Override
    public long failoverClaimLost() {
        return claimLost.get();
    }

    @Override
    public long blockedSipHa() {
        return blockedSipHa.get();
    }
}
