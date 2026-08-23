package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.Fteid;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TeidAllocatorPropertiesTest {

    @Test
    void nodeIndexBounds() {
        assertThrows(IllegalArgumentException.class, () -> new TeidAllocator(-1));
        assertThrows(IllegalArgumentException.class, () -> new TeidAllocator(256));
        new TeidAllocator(0);
        new TeidAllocator(255);
    }

    @Test
    void highByteIsNodeIndexAcrossAllNodes() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.9");
        for (int i = 0; i < 256; i++) {
            Fteid f = new TeidAllocator(i).allocate(ip, 0);
            assertEquals(i, (f.teid() >>> 24) & 0xff);
        }
    }

    @Test
    void allNodesProduceDisjointTeids() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.9");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 256; i++) {
            Fteid f = new TeidAllocator(i).allocate(ip, 0);
            assertTrue(seen.add(f.teid()), "collision at node " + i);
        }
    }

    @Test
    void sequentialWithinNode() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.9");
        TeidAllocator a = new TeidAllocator(1);
        int first = a.allocate(ip, 0).teid();
        int second = a.allocate(ip, 0).teid();
        assertEquals(first + 1, second);
    }

    @Test
    void quarantineDepthEvictsOldest() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.9");
        TeidAllocator a = new TeidAllocator(1, 2);
        Fteid f1 = a.allocate(ip, 0);
        Fteid f2 = a.allocate(ip, 0);
        Fteid f3 = a.allocate(ip, 0);
        a.release(f1.teid());
        a.release(f2.teid());
        a.release(f3.teid());
        assertFalse(a.quarantined(f1.teid())); // evicted
        assertTrue(a.quarantined(f2.teid()));
        assertTrue(a.quarantined(f3.teid()));
    }

    @Test
    void interfaceTypePassedThrough() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.9");
        Fteid f = new TeidAllocator(1).allocate(ip, 7);
        assertEquals(7, f.interfaceType());
        assertEquals("192.0.2.9", f.address().getHostAddress());
    }

    @Test
    void allocateNeverReturnsZeroLocalPart() throws Exception {
        InetAddress ip = InetAddress.getByName("192.0.2.9");
        TeidAllocator a = new TeidAllocator(0);
        for (int i = 0; i < 1000; i++) {
            Fteid f = a.allocate(ip, 0);
            assertTrue((f.teid() & 0x00ff_ffff) != 0);
        }
    }
}