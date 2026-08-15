package et.epc.api.cluster;

import et.epc.api.Fteid;
import java.net.InetAddress;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEID scoped by receiver IP. High 8 bits = node index so N SGW-C/PGW-C slices
 * do not collide. Released TEIDs enter quarantine before reuse.
 */
public final class TeidAllocator {

    private final int nodeShifted;
    private final AtomicInteger seq = new AtomicInteger(1);
    private final ConcurrentLinkedQueue<Integer> quarantine = new ConcurrentLinkedQueue<>();
    private final int quarantineDepth;

    public TeidAllocator(int nodeIndex) {
        this(nodeIndex, 64);
    }

    public TeidAllocator(int nodeIndex, int quarantineDepth) {
        if (nodeIndex < 0 || nodeIndex > 255) {
            throw new IllegalArgumentException("nodeIndex 0–255");
        }
        this.nodeShifted = nodeIndex << 24;
        this.quarantineDepth = quarantineDepth;
    }

    public synchronized Fteid allocate(InetAddress receiver, int interfaceType) {
        Objects.requireNonNull(receiver);
        int local = seq.getAndIncrement() & 0x00ff_ffff;
        if (local == 0) {
            local = seq.getAndIncrement() & 0x00ff_ffff;
        }
        int teid = nodeShifted | local;
        return new Fteid(teid, receiver, interfaceType);
    }

    public synchronized void release(int teid) {
        quarantine.add(teid);
        while (quarantine.size() > quarantineDepth) {
            quarantine.poll();
        }
    }

    public boolean quarantined(int teid) {
        return quarantine.contains(teid);
    }
}
