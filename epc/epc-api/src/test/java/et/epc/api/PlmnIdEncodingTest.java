package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlmnIdEncodingTest {

    @ParameterizedTest
    @CsvSource({
            "001, 01, 00, F1, 10",
            "244, 05, 42, F4, 50",
            "724, 10, 27, F4, 01",
            "311, 480, 13, 01, 84"})
    void threeOctetSnId(String mcc, String mnc, String b0, String b1, String b2) {
        byte[] sn = new PlmnId(mcc, mnc).snId();
        assertArrayEquals(new byte[]{
                (byte) Integer.parseUnsignedInt(b0, 16),
                (byte) Integer.parseUnsignedInt(b1, 16),
                (byte) Integer.parseUnsignedInt(b2, 16)}, sn);
    }

    @ParameterizedTest
    @CsvSource({"001, 01", "310, 410", "999, 99", "001, 001"})
    void accessorsRoundTrip(String mcc, String mnc) {
        PlmnId p = new PlmnId(mcc, mnc);
        assertEquals(mcc, p.mcc());
        assertEquals(mnc, p.mnc());
    }

    @ParameterizedTest
    @CsvSource({
            "001, 01, epc.mnc001.mcc001.3gppnetwork.org",
            "001, 001, epc.mnc001.mcc001.3gppnetwork.org",
            "244, 05, epc.mnc005.mcc244.3gppnetwork.org",
            "310, 410, epc.mnc410.mcc310.3gppnetwork.org"})
    void diameterRealm(String mcc, String mnc, String realm) {
        assertEquals(realm, new PlmnId(mcc, mnc).diameterRealm());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "0000", "12a", "12 "})
    void rejectsBadMcc(String mcc) {
        assertThrows(IllegalArgumentException.class, () -> new PlmnId(mcc, "01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "01a", "0101", "", "  "})
    void rejectsBadMnc(String mnc) {
        assertThrows(IllegalArgumentException.class, () -> new PlmnId("001", mnc));
    }
}