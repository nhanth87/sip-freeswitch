package et.epc.up.linux;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable per-URR counter state; only {@link LinuxUrrRegistry} mutates it. */
final class LinuxUrrCounter {

    private final String urrId;
    private final AtomicLong cumulative = new AtomicLong();
    private final AtomicLong baseline = new AtomicLong();
    private volatile Instant sampledAt = Instant.now();

    LinuxUrrCounter(String urrId, long restoredBaseline) {
        this.urrId = urrId;
        this.baseline.set(restoredBaseline);
        this.cumulative.set(restoredBaseline);
    }

    void add(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must be non-negative");
        }
        cumulative.addAndGet(bytes);
        sampledAt = Instant.now();
    }

    LinuxUrrUsage snapshot() {
        return new LinuxUrrUsage(urrId, cumulative.get(), baseline.get(), sampledAt);
    }

    long cumulative() {
        return cumulative.get();
    }

    long baseline() {
        return baseline.get();
    }

    String urrId() {
        return urrId;
    }

    void advanceBaseline(long value) {
        baseline.set(value);
    }
}