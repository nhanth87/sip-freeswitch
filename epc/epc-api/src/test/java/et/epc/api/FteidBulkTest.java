package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** F-TEID keying / immutability coverage (TS 29.274 scoping). */
class FteidBulkTest {

    static Stream<Arguments> keys() throws Exception {
        return Stream.of(
                Arguments.of(0, "192.0.2.1", 0, "0@192.0.2.1"),
                Arguments.of(1, "192.0.2.1", 6, "1@192.0.2.1"),
                Arguments.of(0x7fffffff, "10.0.0.9", 10, "2147483647@10.0.0.9"),
                Arguments.of(-1, "10.0.0.9", 4, "4294967295@10.0.0.9"),
                Arguments.of(0x100, "2001:db8::1", 5, "256@2001:db8:0:0:0:0:0:1"));
    }

    @ParameterizedTest
    @MethodSource("keys")
    void keyFormatsTeidAtAddress(int teid, String addr, int iface, String key) throws Exception {
        assertEquals(key, new Fteid(teid, InetAddress.getByName(addr), iface).key());
    }

    @Test
    void teidsScopedByAddressDiffer() throws Exception {
        Fteid a = new Fteid(7, InetAddress.getByName("192.0.2.1"), 0);
        Fteid b = new Fteid(7, InetAddress.getByName("192.0.2.2"), 0);
        assertNotEquals(a.key(), b.key());
        assertNotEquals(a, b);
    }

    @Test
    void sameTeidAndAddressAreEqual() throws Exception {
        Fteid a = new Fteid(7, InetAddress.getByName("192.0.2.1"), 0);
        Fteid b = new Fteid(7, InetAddress.getByName("192.0.2.1"), 0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void rejectsNullAddress() {
        assertThrows(NullPointerException.class, () -> new Fteid(1, null, 0));
    }

    @Test
    void unsignedTeidSurvivesRoundTrip() throws Exception {
        int teid = 0x8000_0000;
        Fteid f = new Fteid(teid, InetAddress.getByName("192.0.2.1"), 0);
        assertEquals("2147483648@192.0.2.1", f.key());
    }

    @Test
    void interfaceTypeExposed() throws Exception {
        Fteid f = new Fteid(1, InetAddress.getByName("192.0.2.1"), 6);
        assertEquals(6, f.interfaceType());
    }
}