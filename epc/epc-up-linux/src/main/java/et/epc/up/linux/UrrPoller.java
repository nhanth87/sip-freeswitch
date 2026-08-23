package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * URR charging-loop poller. Each tick: sample kernel counters, feed deltas
 * into the {@link LinuxUrrRegistry}, then turn the registry's delta reports
 * into PFCP usage reports grouped by session SEID. Trigger selection per
 * report: VOLTH when the volume threshold was exceeded since the last report,
 * TIMTH when the time threshold elapsed, else PERIO. A report is emitted when
 * the delta is positive, or on a zero delta when the URR's PERIO trigger is
 * due (periodic heartbeat). Baseline persistence already happens inside
 * {@link LinuxUrrRegistry#poll()}.
 */
public final class UrrPoller {

    private static final Logger LOG = LogManager.getLogger(UrrPoller.class);

    private final LinuxUrrRegistry registry;
    private final UrrCounterSampler sampler;
    private final long pollIntervalMs;
    private final ReportSink sink;
    private final ConcurrentMap<String, Long> lastSampled = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> lastReportAt = new ConcurrentHashMap<>();
    private ScheduledExecutorService executor;

    public UrrPoller(LinuxUrrRegistry registry, UrrCounterSampler sampler,
                     long pollIntervalMs, ReportSink sink) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.sink = Objects.requireNonNull(sink, "sink");
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be positive");
        }
        this.pollIntervalMs = pollIntervalMs;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "epc-urr-poller");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::tickSafely,
                pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized boolean running() {
        return executor != null;
    }

    /** One polling pass; exposed for deterministic tests outside the scheduler. */
    public void tick() {
        Map<String, Long> cumulative;
        try {
            cumulative = sampler.sample();
        } catch (RuntimeException e) {
            LOG.debug("urr sampler failed: {}", e.toString());
            return;
        }
        feedDeltas(cumulative);
        Map<Long, List<PfcpUsageReport>> bySeid = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (LinuxUrrReport report : registry.poll()) {
            Optional<LinuxUrrSpec> specOpt = registry.spec(report.urrId());
            if (specOpt.isEmpty()) {
                continue;
            }
            LinuxUrrSpec spec = specOpt.get();
            boolean perioDue = (spec.reportingTriggers() & PfcpIe.UT_PERIO) != 0;
            if (report.reportedBytes() <= 0 && !perioDue) {
                continue;
            }
            Integer urrId = parseUrrId(report.urrId());
            if (urrId == null) {
                continue;
            }
            Instant last = lastReportAt.computeIfAbsent(report.urrId(), k -> now);
            int duration = (int) Math.min(Integer.MAX_VALUE,
                    Duration.between(last, now).toSeconds());
            int trigger = triggerOf(spec, report.reportedBytes(), duration);
            long volume = report.reportedBytes();
            PfcpUsageReport pfcpReport = spec.uplink()
                    ? new PfcpUsageReport(urrId, trigger, volume, 0L, duration)
                    : new PfcpUsageReport(urrId, trigger, 0L, volume, duration);
            bySeid.computeIfAbsent(spec.seid(), k -> new ArrayList<>()).add(pfcpReport);
            lastReportAt.put(report.urrId(), now);
        }
        for (Map.Entry<Long, List<PfcpUsageReport>> e : bySeid.entrySet()) {
            try {
                sink.report(e.getKey(), List.copyOf(e.getValue()));
            } catch (RuntimeException ex) {
                LOG.warn("urr report sink failed seid={} err={}", e.getKey(), ex.toString());
            }
        }
    }

    private void feedDeltas(Map<String, Long> cumulative) {
        for (Map.Entry<String, Long> e : cumulative.entrySet()) {
            String urrId = e.getKey();
            if (!registry.contains(urrId)) {
                continue;
            }
            long current = e.getValue() == null ? 0L : e.getValue();
            Long prev = lastSampled.put(urrId, current);
            long delta;
            if (prev == null) {
                // First sight: count only bytes beyond the registry's current
                // cumulative (restored baseline), never the whole history.
                long known = registry.readCounter(urrId)
                        .map(LinuxUrrUsage::cumulativeBytes)
                        .orElse(current);
                delta = current - known;
            } else {
                delta = current - prev;
            }
            if (delta < 0) {
                // Kernel counter reset (rule re-created): re-base, no report.
                LOG.debug("urr counter reset urrId={} prev={} current={}", urrId, prev, current);
                continue;
            }
            if (delta > 0) {
                registry.addBytes(urrId, delta);
            }
        }
    }

    private static int triggerOf(LinuxUrrSpec spec, long reportedBytes, int durationSeconds) {
        if (reportedBytes > 0 && spec.volumeThresholdBytes() > 0
                && reportedBytes >= spec.volumeThresholdBytes()) {
            return PfcpIe.UT_VOLTH;
        }
        if (reportedBytes > 0 && spec.timeThresholdSeconds() > 0
                && durationSeconds >= spec.timeThresholdSeconds()) {
            return PfcpIe.UT_TIMTH;
        }
        return PfcpIe.UT_PERIO;
    }

    private static Integer parseUrrId(String urrId) {
        try {
            return Integer.valueOf(urrId);
        } catch (NumberFormatException e) {
            LOG.debug("non-numeric urrId skipped: {}", urrId);
            return null;
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            LOG.warn("urr poll tick failed: {}", t.toString());
        }
    }
}
