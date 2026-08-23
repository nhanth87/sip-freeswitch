package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class FteidTest {

    @Test
    void keyUsesUnsignedTeidAtAddress() throws Exception {
        Fteid f = new Fteid(-1, InetAddress.getByName("192.0.2.1"), 0);
        assertEquals("4294967295@192.0.2.1", f.key());
    }

    @Test
    void keyScopesTeidByReceiverIp() throws Exception {
        Fteid a = new Fteid(5, InetAddress.getByName("10.0.0.1"), 0);
        Fteid b = new Fteid(5, InetAddress.getByName("10.0.0.2"), 0);
        assertEquals("5@10.0.0.1", a.key());
        assertEquals("5@10.0.0.2", b.key());
    }

    @Test
    void nullAddressRejected() {
        assertThrows(NullPointerException.class, () -> new Fteid(1, null, 0));
    }
}