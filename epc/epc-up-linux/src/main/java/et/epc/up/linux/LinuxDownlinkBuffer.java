package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpRule;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Downlink buffering placeholder for the FAR/BUFF apply action. The JVM never holds
 * or relays GTP-U bytes, so this queue holds rule descriptors (per SEID) until the
 * Update-FAR path drains it and installs the real downlink forwarder in the kernel.
 */
public final class LinuxDownlinkBuffer {

    private final ConcurrentMap<Long, Deque<PfcpRule>> pending = new ConcurrentHashMap<>();

    public void enqueue(long seid, PfcpRule dlRule) {
        Objects.requireNonNull(dlRule, "dlRule");
        pending.computeIfAbsent(seid, k -> new ArrayDeque<>()).addLast(dlRule);
    }

    /** Remove and return all queued downlink rules for a session in FIFO order. */
    public List<PfcpRule> drain(long seid) {
        Deque<PfcpRule> q = pending.remove(seid);
        return q == null ? List.of() : List.copyOf(new ArrayList<>(q));
    }

    public int pendingCount(long seid) {
        Deque<PfcpRule> q = pending.get(seid);
        return q == null ? 0 : q.size();
    }

    public int sessions() {
        return pending.size();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }
}