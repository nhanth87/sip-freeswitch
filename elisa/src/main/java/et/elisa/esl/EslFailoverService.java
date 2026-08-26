package et.elisa.esl;

import et.elisa.cluster.FsPairRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * C3: when primary ESL is down, walk {@link FsPairRegistry} failover list and rebind.
 * List is <em>this node only</em> — never cluster-wide "whatever is up"
 * (that would steal another S-CSCF's FS). R9 home pin: retry home FS only.
 */
@ApplicationScoped
public class EslFailoverService {

    private static final Logger LOG = LogManager.getLogger(EslFailoverService.class);

    @Inject FsPairRegistry pairs;
    @Inject FreeSwitchRaBridge bridge;
    @Inject HomeEslRemapService homeRemap;

    private final AtomicInteger cursor = new AtomicInteger(0);
    private ScheduledExecutorService scheduler;

    /** CDI. */
    @Inject
    public EslFailoverService() {
    }

    /** Unit-test wiring. */
    EslFailoverService(FsPairRegistry pairs, FreeSwitchRaBridge bridge, HomeEslRemapService homeRemap) {
        this.pairs = pairs;
        this.bridge = bridge;
        this.homeRemap = homeRemap;
    }

    void onStart(@Observes StartupEvent ev) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "esl-failover");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::probe, 15, 10, TimeUnit.SECONDS);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    void probe() {
        try {
            if (bridge.adaptor() == null && homeRemap == null) {
                return;
            }
            if (bridge.live()) {
                return;
            }
            String pin = homeRemap != null ? homeRemap.pinnedHomeFsNodeId() : null;
            if (pin != null && !pin.isBlank()) {
                retryPinnedHome(pin);
                return;
            }
            if (bridge.adaptor() == null) {
                return;
            }
            List<FsPairRegistry.EslTarget> targets = pairs.targetsForThisNode();
            if (targets.isEmpty()) {
                return;
            }
            int start = Math.floorMod(cursor.get(), targets.size());
            for (int i = 0; i < targets.size(); i++) {
                FsPairRegistry.EslTarget t = targets.get((start + i) % targets.size());
                String active = bridge.activeFsNodeId();
                if (t.fsNodeId().equals(active) && targets.size() > 1) {
                    continue;
                }
                LOG.warn("ESL failover attempting fsNode=%s %s:%d", t.fsNodeId(), t.host(), t.port());
                try {
                    bridge.rebind(t);
                    cursor.set((start + i + 1) % targets.size());
                    if (bridge.live()) {
                        LOG.info("ESL failover ok fsNode=%s", t.fsNodeId());
                        return;
                    }
                } catch (Exception e) {
                    LOG.warn("ESL failover failed fsNode=%s", t.fsNodeId(), e);
                }
            }
            LOG.warn("ESL failover: no live target in pair list");
        } catch (Exception e) {
            LOG.debug("ESL failover probe error", e);
        }
    }

    /** Home pin: retry that FS only. Do not walk this node's pair (would steal). */
    private void retryPinnedHome(String fsNodeId) {
        Optional<FsPairRegistry.EslTarget> home = pairs.targetByFsNodeId(fsNodeId);
        if (home.isEmpty()) {
            LOG.warn("R9 pinned home FS unknown fsNode=%s eslLive=false", fsNodeId);
            return;
        }
        FsPairRegistry.EslTarget t = home.get();
        LOG.warn("R9 retry pinned home FS fsNode=%s %s:%d", t.fsNodeId(), t.host(), t.port());
        try {
            bridge.rebind(t);
        } catch (Exception e) {
            LOG.warn("R9 pinned home retry failed fsNode=%s", t.fsNodeId(), e);
        }
        if (!bridge.live()) {
            LOG.warn("R9 home FS still down fsNode=%s eslLive=false — not moving RTP", t.fsNodeId());
        }
    }
}
