package et.elisa.persist;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Keyed write-behind for Postgres copies of hot (ISPN) state. Callers on SBB / ESL
 * threads only record the latest op per key; one daemon drains them in batched
 * transactions, so PG round trips and commits leave the SIP hot path.
 *
 * <p>Coalescing keeps the queue bounded by live keys (dialogs / channels): several
 * state changes inside one drain window collapse into one row write. PG lags the
 * hot store by at most one interval — it is the cold-restart copy, never the SoT
 * for a live dialog.
 *
 * @param <V> pending op (latest wins per key)
 */
public final class CoalescingWriteBehind<V> {

    /** One transaction per call; must not throw for a single bad row if avoidable. */
    @FunctionalInterface
    public interface BatchWriter<V> {
        void write(List<V> batch);
    }

    private static final Logger LOG = LogManager.getLogger(CoalescingWriteBehind.class);

    private static final int MAX_BATCH = 500;

    private final String name;
    private final BatchWriter<V> writer;
    private final long intervalNanos;
    private final Map<String, V> pending = new ConcurrentHashMap<>();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Thread drainer;
    private volatile boolean stopped;

    public CoalescingWriteBehind(String name, BatchWriter<V> writer, long intervalMs) {
        this.name = name;
        this.writer = writer;
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, intervalMs));
    }

    public void put(String key, V op) {
        pending.put(key, op);
        ensureStarted();
    }

    /** Read-your-writes for PG fallback loads: a queued op wins over the PG row. */
    public V pendingFor(String key) {
        return pending.get(key);
    }

    public int pendingCount() {
        return pending.size();
    }

    public long writtenCount() {
        return written.get();
    }

    public long failedCount() {
        return failed.get();
    }

    /** Drain everything queued so far on the caller thread (shutdown / tests). */
    public void flush() {
        while (!pending.isEmpty()) {
            if (drainOnce() == 0) {
                return;
            }
        }
    }

    public void stop() {
        stopped = true;
        Thread t = drainer;
        if (t != null) {
            LockSupport.unpark(t);
            try {
                t.join(TimeUnit.SECONDS.toMillis(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    private void ensureStarted() {
        if (drainer != null || stopped) {
            return;
        }
        synchronized (this) {
            if (drainer != null || stopped) {
                return;
            }
            Thread t = new Thread(this::run, "elisa-pg-writer-" + name);
            t.setDaemon(true);
            t.start();
            drainer = t;
        }
    }

    private void run() {
        while (!stopped) {
            LockSupport.parkNanos(intervalNanos);
            try {
                while (drainOnce() == MAX_BATCH) {
                    // keep draining full batches before sleeping again
                }
            } catch (RuntimeException ex) {
                LOG.warn("pg write-behind {} drain failed: {}", name, ex.toString());
            }
        }
    }

    private int drainOnce() {
        List<V> batch = new ArrayList<>();
        Iterator<String> keys = pending.keySet().iterator();
        while (keys.hasNext() && batch.size() < MAX_BATCH) {
            V op = pending.remove(keys.next());
            if (op != null) {
                batch.add(op);
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            writer.write(batch);
            written.addAndGet(batch.size());
        } catch (RuntimeException ex) {
            // The hot store still holds live state. Count and move on rather than
            // re-queue over newer ops for the same keys.
            failed.addAndGet(batch.size());
            LOG.warn("pg write-behind {} batch of {} failed: {}", name, batch.size(), ex.toString());
        }
        return batch.size();
    }
}
