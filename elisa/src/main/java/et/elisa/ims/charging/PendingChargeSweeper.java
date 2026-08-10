package et.elisa.ims.charging;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Reconcile deferred pending debits when OCS recovers. Claim-by-key → ProcessCDR → settle
 * (at-most-once; never double-debit).
 */
@ApplicationScoped
public class PendingChargeSweeper {

    private static final Logger LOG = LogManager.getLogger(PendingChargeSweeper.class);

    @Inject CgratesConfig config;
    @Inject PendingChargeStore store;
    @Inject CgratesClient cgrates;
    @Inject SessionSClient sessions;
    @Inject et.elisa.ims.config.ImsRoleFlags roles;

    private ScheduledExecutorService scheduler;

    public PendingChargeSweeper() {}

    public PendingChargeSweeper(CgratesConfig config, PendingChargeStore store,
                                CgratesClient cgrates, SessionSClient sessions) {
        this.config = config;
        this.store = store;
        this.cgrates = cgrates;
        this.sessions = sessions;
    }

    @PostConstruct
    void start() {
        if (config == null || !config.sessionSEnabled()) {
            return;
        }
        int sec = config.pendingSweepIntervalSec();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "elisa-pending-charge-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safeSweep, sec, sec, TimeUnit.SECONDS);
        LOG.info("PendingChargeSweeper scheduled every {}s", sec);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void safeSweep() {
        try {
            reconcileOnce();
        } catch (Throwable t) {
            LOG.warn("PendingChargeSweeper tick soft-fail: {}", t.toString());
        }
    }

    /**
     * Sweep pending debits once. Claim → debit → markSettled; releaseClaim on soft-fail.
     *
     * @return number of pending rows claimed/attempted
     */
    public int reconcileOnce() {
        if (roles != null && !roles.featureCharging()) {
            return 0;
        }
        if (config == null || !config.sessionSEnabled() || !config.enabled()) {
            return 0;
        }
        if (store == null) {
            return 0;
        }
        var pending = store.listPending(100);
        int attempted = 0;
        for (PendingCharge row : pending) {
            if (row == null || row.key() == null) {
                continue;
            }
            var claimed = store.claim(row.key());
            if (claimed.isEmpty()) {
                continue;
            }
            attempted++;
            PendingCharge c = claimed.get();
            boolean ok = debit(c);
            if (ok) {
                store.markSettled(c.key());
                LOG.info("PendingCharge settled key={} account={}", c.key(), c.account());
            } else {
                store.releaseClaim(c.key());
            }
        }
        return attempted;
    }

    private boolean debit(PendingCharge c) {
        try {
            if (cgrates != null) {
                CgratesCdrEvent event = new CgratesCdrEvent(
                        config.tenant(),
                        c.account(),
                        c.destination(),
                        "call",
                        "elisa",
                        "*pseudoprepaid",
                        c.usage() == null || c.usage().isBlank() ? "0s" : c.usage(),
                        c.enqueuedAt(),
                        c.enqueuedAt(),
                        c.icid(),
                        c.sipCallId(),
                        c.sipCallId(),
                        "DEFERRED_DEBIT");
                CgratesClient.Result r = cgrates.processCdrReconcile(event);
                if (r.ok()) {
                    return true;
                }
                if (r.attempted() && !r.ok()) {
                    return false; // peer down — retry
                }
            }
            if (sessions != null) {
                SessionSRequest req = SessionSRequest.hangup(
                        config.tenant(), c.account(), c.destination(),
                        c.icid(), c.sipCallId(), parseUsageSec(c.usage()));
                return sessions.terminateSession(req).ok();
            }
            return false;
        } catch (Throwable t) {
            LOG.warn("PendingCharge debit soft-fail key={}: {}", c.key(), t.toString());
            return false;
        }
    }

    private static Integer parseUsageSec(String usage) {
        if (usage == null || usage.isBlank()) {
            return 0;
        }
        String s = usage.trim();
        if (s.endsWith("s") || s.endsWith("S")) {
            s = s.substring(0, s.length() - 1);
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
