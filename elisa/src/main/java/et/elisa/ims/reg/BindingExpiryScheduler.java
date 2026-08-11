package et.elisa.ims.reg;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Chronos-equivalent lab sweeper: purge expired IMS registration bindings on a timer.
 */
@ApplicationScoped
public class BindingExpiryScheduler {

    private static final Logger LOG = LogManager.getLogger(BindingExpiryScheduler.class);

    @Inject BindingStore bindings;

    @ConfigProperty(name = "sf.ims.reg.expiry-sweep-seconds", defaultValue = "30")
    int sweepSeconds;

    private ScheduledExecutorService exec;
    private final AtomicInteger lastPurged = new AtomicInteger();

    void onStart(@Observes StartupEvent ev) {
        int period = Math.max(5, sweepSeconds);
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ims-binding-expiry");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::safeSweep, period, period, TimeUnit.SECONDS);
        LOG.info("[IMS] BindingExpiryScheduler every {}s", period);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (exec != null) {
            exec.shutdownNow();
        }
    }

    void safeSweep() {
        try {
            int n = bindings.purgeExpired();
            lastPurged.set(n);
            if (n > 0) {
                LOG.info("[IMS] purged {} expired registration binding(s)", n);
            }
        } catch (Throwable t) {
            LOG.warn("[IMS] binding expiry sweep soft-fail: {}", t.toString());
        }
    }

    /** Test / admin hook. */
    public int lastPurgedCount() {
        return lastPurged.get();
    }
}
