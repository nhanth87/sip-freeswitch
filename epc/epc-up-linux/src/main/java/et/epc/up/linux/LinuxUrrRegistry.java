package et.epc.up.linux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * URR counter model + delta poller. Polling advances each counter's baseline so the
 * next report carries only the bytes since the last report, then persists that baseline
 * (optionally throttled by {@code persistIntervalMs} so a fast poll cadence does not
 * write Postgres every tick). A scheduler (or an operator helper) invokes {@link #poll()}
 * periodically; the JVM itself never opens a GTP-U socket or reads kernel counters
 * synchronously on the hot path.
 */
public final class LinuxUrrRegistry {

    private final UrrDatastore datastore;
    private final long persistIntervalMs;
    private final ConcurrentMap<String, LinuxUrrCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LinuxUrrSpec> specs = new ConcurrentHashMap<>();
    private volatile long lastPersistNanos = System.nanoTime();

    public LinuxUrrRegistry(UrrDatastore datastore) {
        this(datastore, 0L);
    }

    /**
     * @param persistIntervalMs throttle for baseline persistence: {@code 0} persists on
     *        every {@link #poll()}, a positive value persists at most once per interval
     *        (in-memory baselines still advance on every poll).
     */
    public LinuxUrrRegistry(UrrDatastore datastore, long persistIntervalMs) {
        this.datastore = Objects.requireNonNull(datastore, "datastore");
        if (persistIntervalMs < 0) {
            throw new IllegalArgumentException("persistIntervalMs must be non-negative");
        }
        this.persistIntervalMs = persistIntervalMs;
    }

    /** Register a URR counter, or restore its baseline from the datastore if already seen. */
    public void create(String urrId) {
        Objects.requireNonNull(urrId, "urrId");
        counters.computeIfAbsent(urrId, id ->
                new LinuxUrrCounter(id, datastore.loadBaseline(id).orElse(0L)));
    }

    /** Register a URR with its PFCP session context (owning SEID + reporting thresholds). */
    public void create(String urrId, LinuxUrrSpec spec) {
        create(urrId);
        specs.put(urrId, Objects.requireNonNull(spec, "spec"));
    }

    /** PFCP session context of a URR, when installed with {@link #create(String, LinuxUrrSpec)}. */
    public Optional<LinuxUrrSpec> spec(String urrId) {
        return Optional.ofNullable(specs.get(urrId));
    }

    /** True when a counter exists for the URR (spec-less counters included). */
    public boolean contains(String urrId) {
        return counters.containsKey(urrId);
    }

    /** Accumulate counted bytes into the URR counter (delta feed from a kernel sampler). */
    public void addBytes(String urrId, long bytes) {
        counter(urrId).add(bytes);
    }

    /** On-demand read for PFCP Query-URR; does not advance the report baseline. */
    public Optional<LinuxUrrUsage> readCounter(String urrId) {
        LinuxUrrCounter c = counters.get(urrId);
        return c == null ? Optional.empty() : Optional.of(c.snapshot());
    }

    /**
     * One polling pass: emit delta-not-cumulative reports, advance each baseline and
     * persist it (throttled by the persist interval). Deterministic order (by urrId)
     * so tests and reports are reproducible.
     */
    public List<LinuxUrrReport> poll() {
        List<LinuxUrrCounter> ordered = new ArrayList<>(counters.values());
        ordered.sort(Comparator.comparing(LinuxUrrCounter::urrId));
        List<LinuxUrrReport> reports = new ArrayList<>(ordered.size());
        for (LinuxUrrCounter c : ordered) {
            long cumulative = c.cumulative();
            long delta = cumulative - c.baseline();
            c.advanceBaseline(cumulative);
            reports.add(new LinuxUrrReport(c.urrId(), delta, cumulative, Instant.now()));
        }
        persistBaselines(false);
        return List.copyOf(reports);
    }

    /** Flush all current baselines to the datastore without emitting reports. */
    public void persist() {
        persistBaselines(true);
    }

    public void remove(String urrId) {
        counters.remove(urrId);
        specs.remove(urrId);
        datastore.delete(urrId);
    }

    public int size() {
        return counters.size();
    }

    private void persistBaselines(boolean force) {
        long now = System.nanoTime();
        if (!force && persistIntervalMs > 0
                && TimeUnit.NANOSECONDS.toMillis(now - lastPersistNanos) < persistIntervalMs) {
            return;
        }
        lastPersistNanos = now;
        counters.values().forEach(c -> datastore.saveBaseline(c.urrId(), c.baseline()));
    }

    private LinuxUrrCounter counter(String urrId) {
        LinuxUrrCounter c = counters.get(Objects.requireNonNull(urrId, "urrId"));
        if (c == null) {
            throw new IllegalArgumentException("unknown urrId: " + urrId);
        }
        return c;
    }
}
