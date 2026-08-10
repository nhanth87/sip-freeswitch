package et.elisa.ims.charging;

import et.elisa.ims.config.ImsRoleFlags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SessionS INVITE gate + deferred debit (grill G3). Soft-fail; never hard-403 when
 * {@code sf.ocs.mode=deferred}. Credit deny when OCS up → {@link Decision#REDIRECT_302}.
 */
@ApplicationScoped
public class OnlineChargingService {

    private static final Logger LOG = LogManager.getLogger(OnlineChargingService.class);

    public enum Decision {
        CONTINUE,
        REDIRECT_302
    }

    @Inject ImsRoleFlags roles;
    @Inject CgratesConfig config;
    @Inject SessionSClient sessions;
    @Inject PendingChargeStore pending;
    @Inject RfAcrEmitter rf;

    /** Call-ID → ICID correlation (service-scoped; not SBB instance state). */
    private final ConcurrentHashMap<String, String> icidByCallId = new ConcurrentHashMap<>();

    public OnlineChargingService() {}

    public OnlineChargingService(ImsRoleFlags roles, CgratesConfig config, SessionSClient sessions,
                                 PendingChargeStore pending, RfAcrEmitter rf) {
        this.roles = roles;
        this.config = config;
        this.sessions = sessions;
        this.pending = pending;
        this.rf = rf;
    }

    public boolean active() {
        return roles != null && roles.featureCharging()
                && config != null && config.enabled() && config.sessionSEnabled();
    }

    /**
     * INVITE path: InitiateSession when SessionS on; OCS down + deferred → enqueue + continue.
     */
    public Decision onInvite(String icid, String sipCallId, String account, String destination) {
        try {
            if (sipCallId != null && icid != null && !icid.isBlank()) {
                icidByCallId.put(sipCallId, icid);
            }
            if (!active()) {
                return Decision.CONTINUE;
            }
            if (account == null || account.isBlank()) {
                LOG.debug("SessionS skip INVITE — no account sipCallId={}", sipCallId);
                return Decision.CONTINUE;
            }
            SessionSRequest req = SessionSRequest.invite(
                    config.tenant(), account, destination, icid, sipCallId);
            SessionSClient.Result r = sessions.initiateSession(req);
            if (r.ok()) {
                return Decision.CONTINUE;
            }
            if (r.creditDenied()) {
                LOG.info("SessionS credit deny account={} icid={} → 302", account, icid);
                return Decision.REDIRECT_302;
            }
            if (r.peerDown() || !r.ok()) {
                if (config.ocsDeferred()) {
                    enqueueSoftAllow(icid, sipCallId, account, destination);
                    LOG.info("SessionS OCS down deferred soft-continue icid={} sipCallId={}",
                            icid, sipCallId);
                    return Decision.CONTINUE;
                }
                LOG.warn("SessionS OCS down live-mode soft-continue icid={} detail={}",
                        icid, r.detail());
                return Decision.CONTINUE;
            }
            return Decision.CONTINUE;
        } catch (Throwable t) {
            LOG.warn("OnlineCharging onInvite soft-fail: {}", t.toString());
            if (config != null && config.ocsDeferred()) {
                enqueueSoftAllow(icid, sipCallId, account, destination);
            }
            return Decision.CONTINUE;
        }
    }

    /** INVITE confirmed → Rf ACR START (offline dual-write path). */
    public void onConfirmed(String icid, String sipCallId, String account) {
        try {
            String resolved = resolveIcid(icid, sipCallId);
            if (roles == null || !roles.featureCharging() || config == null || !config.rfDualWriteActive()) {
                return;
            }
            if (rf != null) {
                rf.emitStart(RfAcrRequest.start(resolved, sipCallId, account));
            }
        } catch (Throwable t) {
            LOG.warn("Rf START soft-fail: {}", t.toString());
        }
    }

    /**
     * BYE / hangup: TerminateSession when live session; else finalize pending usage for sweeper.
     */
    public void onHangup(String icid, String sipCallId, String account, String destination,
                         Integer durationSec) {
        try {
            String resolved = resolveIcid(icid, sipCallId);
            if (sipCallId != null) {
                icidByCallId.remove(sipCallId);
            }
            if (roles == null || !roles.featureCharging()) {
                return;
            }
            String key = PendingCharge.keyOf(resolved, sipCallId);
            if (key != null && pending != null) {
                var existing = pending.findByKey(key);
                if (existing.isPresent() && PendingCharge.STATE_PENDING.equals(existing.get().state())) {
                    pending.finalizeUsage(key, destination, CgratesCdrEvent.usageSeconds(durationSec));
                    return;
                }
            }
            if (active() && sessions != null && account != null && !account.isBlank()) {
                SessionSRequest req = SessionSRequest.hangup(
                        config.tenant(), account, destination, resolved, sipCallId, durationSec);
                SessionSClient.Result r = sessions.terminateSession(req);
                if (r.peerDown() && config.ocsDeferred()) {
                    enqueueSoftAllow(resolved, sipCallId, account, destination);
                    if (key != null) {
                        pending.finalizeUsage(key, destination, CgratesCdrEvent.usageSeconds(durationSec));
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("OnlineCharging onHangup soft-fail: {}", t.toString());
        }
    }

    private String resolveIcid(String icid, String sipCallId) {
        if (icid != null && !icid.isBlank()) {
            return icid;
        }
        if (sipCallId == null) {
            return null;
        }
        return icidByCallId.get(sipCallId);
    }

    private void enqueueSoftAllow(String icid, String sipCallId, String account, String destination) {
        if (pending == null) {
            return;
        }
        try {
            pending.enqueue(PendingCharge.pending(icid, sipCallId, account, destination, "60s"));
        } catch (Throwable t) {
            LOG.warn("pending enqueue soft-fail: {}", t.toString());
        }
    }
}
