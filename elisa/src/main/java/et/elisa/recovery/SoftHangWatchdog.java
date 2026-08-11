package et.elisa.recovery;

import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.cmp.InviteCmpSnapshot;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Soft-hang: locally owned EARLY/CONFIRMED dialogs with stale CMP {@code updatedAt}
 * trigger {@link SbbRecoveryManager#onEntitySuspect}.
 */
@ApplicationScoped
public class SoftHangWatchdog {

    private static final Logger LOG = Logger.getLogger(SoftHangWatchdog.class);

    @Inject SbbRecoveryManagerImpl recovery;
    @Inject DialogOwnerStore owners;
    @Inject DialogCmpPersistence dialogCmp;

    @ConfigProperty(name = "sf.cluster.soft-hang.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.cluster.soft-hang.interval-seconds", defaultValue = "30")
    long intervalSeconds;

    @ConfigProperty(name = "sf.cluster.soft-hang.stale-seconds", defaultValue = "60")
    long staleSeconds;

    private ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<String, Long> lastSuspectMs = new ConcurrentHashMap<>();

    @PostConstruct
    void start() {
        if (!enabled) {
            LOG.info("SoftHangWatchdog disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "elisa-soft-hang");
            t.setDaemon(true);
            return t;
        });
        long period = Math.max(5L, intervalSeconds);
        scheduler.scheduleWithFixedDelay(this::scan, period, period, TimeUnit.SECONDS);
        LOG.infof("SoftHangWatchdog interval=%ds stale=%ds", period, staleSeconds);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void scan() {
        try {
            scanAt(System.currentTimeMillis());
        } catch (RuntimeException ex) {
            LOG.debugf(ex, "soft-hang scan failed");
        }
    }

    /** Package-visible for unit tests. */
    int scanAt(long nowEpochMs) {
        if (owners == null || dialogCmp == null || recovery == null) {
            return 0;
        }
        String local = owners.localNodeId();
        long staleMs = Math.max(5L, staleSeconds) * 1000L;
        int suspects = 0;
        for (DialogOwner owner : owners.listOwnedBy(local)) {
            if (owner == null || owner.callId() == null) {
                continue;
            }
            InviteCmpSnapshot snap = dialogCmp.load(owner.callId()).orElse(null);
            if (snap == null || !SbbRecoveryManagerImpl.isRecoverableDialogState(snap.dialogState())) {
                continue;
            }
            long updated = snap.updatedAtEpochMs();
            if (updated <= 0 || nowEpochMs - updated < staleMs) {
                continue;
            }
            Long prev = lastSuspectMs.get(owner.callId());
            if (prev != null && nowEpochMs - prev < staleMs) {
                continue;
            }
            lastSuspectMs.put(owner.callId(), nowEpochMs);
            recovery.onEntitySuspect(SbbRecoveryManagerImpl.stableInviteEntityId(owner.callId()), updated);
            suspects++;
        }
        if (suspects > 0) {
            LOG.infof("soft-hang suspects=%d node=%s", suspects, local);
        }
        return suspects;
    }
}
