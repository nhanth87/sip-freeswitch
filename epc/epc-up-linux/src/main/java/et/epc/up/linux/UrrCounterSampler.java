package et.epc.up.linux;

import java.util.Map;

/**
 * Samples cumulative per-URR byte counters from the kernel dataplane
 * (nftables set counters today). The {@link UrrPoller} derives deltas between
 * successive samples and feeds them into the {@link LinuxUrrRegistry}. Runs off
 * the hot path only — never on packet forwarding, and the JVM never opens a
 * GTP-U socket.
 */
@FunctionalInterface
public interface UrrCounterSampler {

    /**
     * Cumulative byte counters keyed by urrId. Implementations return an
     * empty map on sampler failure (soft-fail) and omit UE elements that do
     * not map to a registered URR.
     */
    Map<String, Long> sample();
}
