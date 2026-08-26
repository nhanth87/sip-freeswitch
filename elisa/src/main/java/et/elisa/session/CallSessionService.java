package et.elisa.session;

import et.elisa.cluster.CallSession;
import et.elisa.cluster.CallSessionStore;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import et.elisa.cmp.SipCallIds;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCallSession;
import et.elisa.profile.ElisaProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String elisaNodeId;

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
        CallSession session = hot != null ? hot.byFsUuid(fsUuid).orElse(null) : null;
        if (session != null) {
            CallSession ended = session.withState(hangupState == null ? "HANGUP" : hangupState);
            hot.remove(session);
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
        SfCallSession row = SfCallSession.find("fsUuid", s.fsUuid()).firstResult();
        if (row == null) {
            row = new SfCallSession();
            row.fsUuid = s.fsUuid();
            row.startedAt = s.startedAt();
        }
        row.sipCallId = s.sipCallId();
        row.profileId = s.profileId();
        row.msisdn = s.msisdn();
        row.tenantId = s.tenantId();
        row.elisaNodeId = s.elisaNodeId();
        row.fsNodeId = s.fsNodeId();
        row.direction = s.direction();
        row.state = s.state();
        row.updatedAt = s.updatedAt();
        row.persist();
    }

    private void markEndedPg(CallSession s) {
        SfCallSession row = SfCallSession.find("fsUuid", s.fsUuid()).firstResult();
        if (row == null) {
            persistPg(s);
            row = SfCallSession.find("fsUuid", s.fsUuid()).firstResult();
        }
        if (row != null) {
            row.state = s.state();
            row.updatedAt = Instant.now();
            row.endedAt = Instant.now();
            row.persist();
        }
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
