package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Volume/edge coverage for PLMN 3-octet SN-id encoding (TS 24.301). */
class PlmnIdBulkTest {

    static Stream<Arguments> snIdCases() {
        return Stream.of(
                Arguments.of("001", "01", "00F110"),
                Arguments.of("244", "05", "42F450"),
                Arguments.of("724", "10", "27F401"),
                Arguments.of("311", "480", "130184"),
                Arguments.of("310", "410", "130014"),
                Arguments.of("001", "001", "001100"),
                Arguments.of("999", "99", "99F999"),
                Arguments.of("999", "999", "999999"),
                Arguments.of("123", "45", "21F354"),
                Arguments.of("123", "456", "216354"));
    }

    @ParameterizedTest
    @MethodSource("snIdCases")
    void snIdMatchesExpectedBytes(String mcc, String mnc, String expectedHex) {
        byte[] expect = new byte[]{
                (byte) Integer.parseUnsignedInt(expectedHex.substring(0, 2), 16),
                (byte) Integer.parseUnsignedInt(expectedHex.substring(2, 4), 16),
                (byte) Integer.parseUnsignedInt(expectedHex.substring(4, 6), 16)};
        assertArrayEquals(expect, new PlmnId(mcc, mnc).snId());
    }

    static Stream<Arguments> realms() {
        return Stream.of(
                Arguments.of("001", "01", "epc.mnc001.mcc001.3gppnetwork.org"),
                Arguments.of("001", "001", "epc.mnc001.mcc001.3gppnetwork.org"),
                Arguments.of("244", "05", "epc.mnc005.mcc244.3gppnetwork.org"),
                Arguments.of("310", "410", "epc.mnc410.mcc310.3gppnetwork.org"),
                Arguments.of("724", "10", "epc.mnc010.mcc724.3gppnetwork.org"),
                Arguments.of("999", "999", "epc.mnc999.mcc999.3gppnetwork.org"));
    }

    @ParameterizedTest
    @MethodSource("realms")
    void diameterRealmPadsTwoDigitMnc(String mcc, String mnc, String realm) {
        assertEquals(realm, new PlmnId(mcc, mnc).diameterRealm());
    }

    static Stream<Arguments> roundTrips() {
        Stream.Builder<Arguments> b = Stream.builder();
        for (String mcc : new String[]{"001", "123", "310", "234", "450", "602", "724", "999"}) {
            for (String mnc : new String[]{"01", "05", "10", "99", "001", "410", "999"}) {
                b.add(Arguments.of(mcc, mnc));
            }
        }
        return b.build();
    }

    @ParameterizedTest
    @MethodSource("roundTrips")
    void everyValidPairRoundTripsOnceEncoded(String mcc, String mnc) {
        PlmnId p = new PlmnId(mcc, mnc);
        assertEquals(3, p.snId().length);
        assertEquals(p, new PlmnId(p.mcc(), p.mnc()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "00", "0000", "12a", "12 ", " 12", "", "abc", "12.", "0x1"})
    void rejectsInvalidMcc(String mcc) {
        assertThrows(IllegalArgumentException.class, () -> new PlmnId(mcc, "01"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "01a", "0101", "", "  ", "99a", "0x1", "9 ", "12."})
    void rejectsInvalidMnc(String mnc) {
        assertThrows(IllegalArgumentException.class, () -> new PlmnId("001", mnc));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "", "  "})
    void rejectsNullLikeMcc(String mcc) {
        assertThrows(IllegalArgumentException.class, () -> new PlmnId(mcc, "01"));
    }

    static Stream<Arguments> sortedMccMnc() {
        return Stream.of(
                Arguments.of("000", "00"), Arguments.of("000", "000"),
                Arguments.of("001", "01"), Arguments.of("999", "99"));
    }

    @ParameterizedTest
    @MethodSource("sortedMccMnc")
    void boundaryMccMncAreValid(String mcc, String mnc) {
        assertTrue(new PlmnId(mcc, mnc).snId().length == 3);
    }
}