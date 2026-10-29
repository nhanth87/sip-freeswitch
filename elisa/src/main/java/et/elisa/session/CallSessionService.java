package et.elisa.session;

import et.elisa.cluster.CallSession;
import et.elisa.cluster.CallSessionStore;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import et.elisa.cmp.SipCallIds;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.persist.CoalescingWriteBehind;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCallSession;
import et.elisa.profile.ElisaProfileService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/** Dual-write CallSession (ISPN + PG + SLEE Profile) — grill P2 / JP1. */
@ApplicationScoped
public class CallSessionService {

    private static final Logger LOG = LogManager.getLogger(CallSessionService.class);

    @Inject CallSessionStore hot;
    @Inject DialogCmpPersistence dialogCmp;
    @Inject HssLiteService hss;
    @Inject ElisaProfileService sleeProfiles;

    @Inject CallSessionPgWriter pgWriter;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String elisaNodeId;

    /**
     * FS fires CREATE/PROGRESS/ANSWER/… then HANGUP/HANGUP_COMPLETE/DESTROY per channel;
     * a synchronous find+persist per event made ESL traffic the PG bottleneck. The PG
     * copy is written behind (coalesced per Unique-ID); 0 = synchronous.
     */
    @ConfigProperty(name = "sf.session.pg-write-behind-ms", defaultValue = "50")
    long writeBehindMs = 50L;

    private volatile CoalescingWriteBehind<CallSessionPgWriter.Op> writeBehind;

    /** Channels already ended: the 2nd/3rd hangup event must not fall through to PG. */
    private static final long ENDED_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int ENDED_MAX = 200_000;
    private final ConcurrentHashMap<String, Long> recentlyEnded = new ConcurrentHashMap<>();

    @PreDestroy
    void shutdown() {
        CoalescingWriteBehind<CallSessionPgWriter.Op> wb = writeBehind;
        if (wb != null) {
            wb.stop();
        }
    }

    @Transactional
    public CallSession upsertFromFs(String fsUuid, String sipCallId, String caller, String callee,
                                    String direction, String state, String fsNodeId) {
        if (fsUuid == null || fsUuid.isBlank()) {
            return null;
        }
        Optional<ImsSubscriber> profile = hss.resolveIdentity(caller)
                .or(() -> hss.resolveIdentity(callee));
        Long profileId = profile.map(p -> p.id).orElse(null);
        String msisdn = profile.map(p -> p.msisdn != null ? p.msisdn : p.directoryUserId()).orElse(null);
        String tenantId = profile.map(p -> p.tenantId).orElse(null);

        Instant now = Instant.now();
        CallSession existing = hot != null ? hot.byFsUuid(fsUuid).orElse(null) : null;
        Instant started = existing != null ? existing.startedAt() : now;
        String sip = sipCallId != null && !sipCallId.isBlank()
                ? SipCallIds.canonical(sipCallId)
                : (existing != null ? existing.sipCallId() : null);
        CallSession session = new CallSession(
                fsUuid, sip, profileId, msisdn, tenantId,
                elisaNodeId, fsNodeId, direction,
                state == null ? "CREATED" : state,
                started, now);
        if (hot != null) {
            hot.put(session);
        }
        persistPg(session);
        if (sleeProfiles != null) {
            sleeProfiles.upsertCallSession(
                    session.fsUuid(), session.sipCallId(), session.msisdn(), session.tenantId(),
                    session.elisaNodeId(), session.fsNodeId(), session.direction(), session.state(),
                    session.startedAt().toEpochMilli(), session.updatedAt().toEpochMilli());
        }

        if (sip != null && dialogCmp != null) {
            Optional<InviteCmpSnapshot> priorCmp = dialogCmp.load(sip);
            if (priorCmp.isPresent()) {
                InviteCmpSnapshot linked = priorCmp.get().withFs(fsUuid, msisdn);
                if (state != null && (state.contains("ANSWER") || "ANSWERED".equals(state))) {
                    linked = linked.withState(DialogCheckpoint.STATE_CONFIRMED);
                }
                dialogCmp.save(linked);
            } else {
                long ms = now.toEpochMilli();
                String dialogState = state != null && (state.contains("ANSWER") || "ANSWERED".equals(state))
                        ? DialogCheckpoint.STATE_CONFIRMED
                        : DialogCheckpoint.STATE_INVITE;
                dialogCmp.save(new InviteCmpSnapshot(
                        sip, fsUuid, msisdn, caller, callee, null, null,
                        fsNodeId, elisaNodeId, dialogState, ms, ms));
            }
        }
        LOG.debug("call_session upsert fs=%s sip=%s msisdn=%s state=%s", fsUuid, sip, msisdn, state);
        return session;
    }

    @Transactional
    public void endByFsUuid(String fsUuid, String hangupState) {
        if (fsUuid == null) {
            return;
        }
        if (isRecentlyEnded(fsUuid)) {
            return;
        }
        CallSession session = hot != null ? hot.byFsUuid(fsUuid).orElse(null) : null;
        if (session != null) {
            CallSession ended = session.withState(hangupState == null ? "HANGUP" : hangupState);
            hot.remove(session);
            markRecentlyEnded(fsUuid);
            markEndedPg(ended);
            if (ended.sipCallId() != null && dialogCmp != null) {
                dialogCmp.remove(ended.sipCallId());
            }
        } else {
            SfCallSession row = SfCallSession.find("fsUuid", fsUuid).firstResult();
            if (row != null && row.endedAt == null) {
                row.state = hangupState == null ? "HANGUP" : hangupState;
                row.endedAt = Instant.now();
                row.updatedAt = Instant.now();
                row.persist();
            }
        }
        if (sleeProfiles != null) {
            sleeProfiles.endCallSession(fsUuid, hangupState);
        }
    }

    public Optional<CallSession> findByFsUuid(String fsUuid) {
        if (fsUuid == null) {
            return Optional.empty();
        }
        if (hot != null) {
            Optional<CallSession> hit = hot.byFsUuid(fsUuid);
            if (hit.isPresent()) {
                return hit;
            }
        }
        if (isRecentlyEnded(fsUuid)) {
            return Optional.empty();
        }
        CoalescingWriteBehind<CallSessionPgWriter.Op> wb = writeBehind;
        CallSessionPgWriter.Op queued = wb == null ? null : wb.pendingFor(fsUuid);
        if (queued != null) {
            return queued.ended() ? Optional.empty() : Optional.of(queued.session());
        }
        return loadPg(fsUuid).map(this::toHot);
    }

    public Optional<CallSession> findBySipCallId(String sipCallId) {
        if (sipCallId == null) {
            return Optional.empty();
        }
        String canon = SipCallIds.canonical(sipCallId);
        if (hot != null) {
            Optional<CallSession> hit = hot.bySipCallId(canon);
            if (hit.isPresent()) {
                return hit;
            }
        }
        try {
            SfCallSession row = SfCallSession.find("sipCallId", canon).firstResult();
            if (row == null && !canon.equals(sipCallId)) {
                row = SfCallSession.find("sipCallId", sipCallId).firstResult();
            }
            return row == null ? Optional.empty() : Optional.of(toHot(row));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    public List<CallSession> listLive() {
        List<CallSession> live = hot.listLive();
        if (!live.isEmpty()) {
            return live;
        }
        return SfCallSession.<SfCallSession>list("endedAt is null").stream().map(this::toHot).toList();
    }

    @Transactional
    public List<SfCallSession> listLivePg(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return SfCallSession.list("endedAt is null order by startedAt desc");
        }
        return SfCallSession.list("endedAt is null and tenantId = ?1 order by startedAt desc", tenantId);
    }

    private void persistPg(CallSession s) {
        writePg(new CallSessionPgWriter.Op(s, false));
    }

    private void markEndedPg(CallSession s) {
        writePg(new CallSessionPgWriter.Op(s, true));
    }

    private void writePg(CallSessionPgWriter.Op op) {
        CallSessionPgWriter writer = pgWriter;
        if (writer == null) {
            return;
        }
        CoalescingWriteBehind<CallSessionPgWriter.Op> wb = writeBehind();
        if (wb != null) {
            wb.put(op.session().fsUuid(), op);
        } else {
            writer.writeBatch(List.of(op));
        }
    }

    private CoalescingWriteBehind<CallSessionPgWriter.Op> writeBehind() {
        CoalescingWriteBehind<CallSessionPgWriter.Op> wb = writeBehind;
        if (wb != null || writeBehindMs <= 0) {
            return wb;
        }
        synchronized (this) {
            if (writeBehind == null) {
                writeBehind = new CoalescingWriteBehind<>("call-session", pgWriter::writeBatch, writeBehindMs);
            }
            return writeBehind;
        }
    }

    private void markRecentlyEnded(String fsUuid) {
        if (recentlyEnded.size() >= ENDED_MAX) {
            long now = System.nanoTime();
            recentlyEnded.values().removeIf(at -> now - at > ENDED_TTL_NANOS);
            if (recentlyEnded.size() >= ENDED_MAX) {
                recentlyEnded.clear();
            }
        }
        recentlyEnded.put(fsUuid, System.nanoTime());
    }

    private boolean isRecentlyEnded(String fsUuid) {
        Long at = recentlyEnded.get(fsUuid);
        if (at == null) {
            return false;
        }
        if (System.nanoTime() - at > ENDED_TTL_NANOS) {
            recentlyEnded.remove(fsUuid, at);
            return false;
        }
        return true;
    }

    private Optional<SfCallSession> loadPg(String fsUuid) {
        try {
            return Optional.ofNullable(SfCallSession.find("fsUuid", fsUuid).firstResult());
        } catch (RuntimeException ex) {
            // Unit stubs / no Quarkus Arc — treat as miss
            return Optional.empty();
        }
    }

    private CallSession toHot(SfCallSession row) {
        return new CallSession(
                row.fsUuid, row.sipCallId, row.profileId, row.msisdn, row.tenantId,
                row.elisaNodeId, row.fsNodeId, row.direction, row.state,
                row.startedAt, row.updatedAt);
    }
}
