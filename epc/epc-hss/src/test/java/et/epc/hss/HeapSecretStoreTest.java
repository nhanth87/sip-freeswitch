package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import et.epc.api.Imsi;
import et.epc.crypto.Bytes;
import org.junit.jupiter.api.Test;

class HeapSecretStoreTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    @Test
    void returnsClonesByReference() {
        HeapSecretStore s = new HeapSecretStore();
        byte[] ki = Bytes.parseHex("00112233445566778899aabbccddeeff");
        byte[] opc = Bytes.parseHex("ffeeddccbbaa99887766554433221100");
        byte[] amf = Bytes.parseHex("8000");
        s.put(IMSI, ki, opc, amf);
        assertArrayEquals(ki, s.ki(IMSI));
        assertArrayEquals(opc, s.opc(IMSI));
        assertArrayEquals(amf, s.amf(IMSI));
    }

    @Test
    void mutatingSourceAfterPutDoesNotAffectStore() {
        HeapSecretStore s = new HeapSecretStore();
        byte[] ki = Bytes.parseHex("00112233445566778899aabbccddeeff");
        s.put(IMSI, ki, Bytes.parseHex("ffeeddccbbaa99887766554433221100"), Bytes.parseHex("8000"));
        ki[0] = 0;
        assertArrayEquals(Bytes.parseHex("00112233445566778899aabbccddeeff"), s.ki(IMSI));
    }

    @Test
    void mutatingReturnedArrayDoesNotAffectStore() {
        HeapSecretStore s = new HeapSecretStore();
        s.put(IMSI, Bytes.parseHex("00112233445566778899aabbccddeeff"),
                Bytes.parseHex("ffeeddccbbaa99887766554433221100"), Bytes.parseHex("8000"));
        s.ki(IMSI)[0] = 0;
        assertArrayEquals(Bytes.parseHex("00112233445566778899aabbccddeeff"), s.ki(IMSI));
    }

    @Test
    void unknownImsiThrows() {
        HeapSecretStore s = new HeapSecretStore();
        assertThrows(IllegalArgumentException.class, () -> s.ki(IMSI));
        assertThrows(IllegalArgumentException.class, () -> s.opc(IMSI));
        assertThrows(IllegalArgumentException.class, () -> s.amf(IMSI));
    }
}