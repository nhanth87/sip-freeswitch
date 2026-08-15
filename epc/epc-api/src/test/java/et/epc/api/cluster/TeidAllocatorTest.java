package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Fteid;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class TeidAllocatorTest {

    @Test
    void nodeSlicesDoNotCollide() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.1");
        TeidAllocator a = new TeidAllocator(1);
        TeidAllocator b = new TeidAllocator(2);
        Fteid fa = a.allocate(ip, 0);
        Fteid fb = b.allocate(ip, 0);
        assertEquals(1, (fa.teid() >>> 24) & 0xff);
        assertEquals(2, (fb.teid() >>> 24) & 0xff);
        assertNotEquals(fa.teid(), fb.teid());
    }

    @Test
    void releasedTeidIsQuarantined() throws Exception {
        TeidAllocator a = new TeidAllocator(3, 8);
        Fteid f = a.allocate(InetAddress.getByName("192.0.2.8"), 1);
        a.release(f.teid());
        assertTrue(a.quarantined(f.teid()));
    }

    @Test
    void epcClusterMustNotJoinElisa() {
        assertThrows(IllegalArgumentException.class,
                () -> new EpcClusterConfig("n1", ClusterMode.DIST_SYNC, "elisa-cluster", 1));
    }
}
