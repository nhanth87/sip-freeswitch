package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlmnIdTest {

    @Test
    void snId_twoDigitMnc() {
        // MCC=001 MNC=01 → 00 F1 10
        byte[] sn = new PlmnId("001", "01").snId();
        assertArrayEquals(new byte[] {0x00, (byte) 0xF1, 0x10}, sn);
    }

    @Test
    void diameterRealm_padsMnc() {
        assertEquals("epc.mnc001.mcc001.3gppnetwork.org", new PlmnId("001", "01").diameterRealm());
    }
}
