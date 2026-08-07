package et.elisa.esl;

import et.elisa.cluster.FsPairRegistry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

/**
 * C3: when primary ESL is down, walk {@link FsPairRegistry} failover list and rebind.
 */
@ApplicationScoped
public class EslFailoverService {

    private static final Logger LOG = Logger.getLogger(EslFailoverService.class);

    @Inject FsPairRegistry pairs;
    @Inject FreeSwitchRaBridge bridge;

    private final AtomicInteger cursor = new AtomicInteger(0);
    private ScheduledExecutorService scheduler;

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
            if (bridge.adaptor() == null) {
                return;
            }
            if (bridge.live()) {
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
                LOG.warnf("ESL failover attempting fsNode=%s %s:%d", t.fsNodeId(), t.host(), t.port());
                try {
                    bridge.rebind(t);
                    cursor.set((start + i + 1) % targets.size());
                    if (bridge.live()) {
                        LOG.infof("ESL failover ok fsNode=%s", t.fsNodeId());
                        return;
                    }
                } catch (Exception e) {
                    LOG.warnf(e, "ESL failover failed fsNode=%s", t.fsNodeId());
                }
            }
            LOG.warn("ESL failover: no live target in pair list");
        } catch (Exception e) {
            LOG.debugf(e, "ESL failover probe error");
        }
    }
}
