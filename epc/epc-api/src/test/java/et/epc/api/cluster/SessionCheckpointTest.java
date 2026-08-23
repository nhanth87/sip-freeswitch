package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.BearerId;
import et.epc.api.Imsi;
import et.epc.api.SessionKeys;
import et.epc.api.UeAddresses;
import org.junit.jupiter.api.Test;

class SessionCheckpointTest {

    private static SessionKeys keys() {
        return new SessionKeys(new Imsi("001010000000001"), "internet", new BearerId(7));
    }

    private static SessionCheckpoint cp() {
        return new SessionCheckpoint(
                SessionCheckpoint.sgwId(keys()), "sgw", "001010000000001", "internet", 7,
                "epc-1", 3L, "10.64.0.2", "11@192.0.2.1", "22@192.0.2.2", "33@192.0.2.3",
                99L, "00000000000000aa", 0x12345, 0xaaL);
    }

    @Test
    void sgwAndPgwIdsAreNamespaced() {
        assertEquals("sgw|001010000000001/internet/7", SessionCheckpoint.sgwId(keys()));
        assertEquals("pgw|001010000000001/internet/7", SessionCheckpoint.pgwId(keys()));
    }

    @Test
    void imsiAndKeysRoundTrip() {
        SessionCheckpoint c = cp();
        assertEquals("001010000000001", c.imsi().digits());
        assertEquals(7, c.keys().defaultBearer().ebi());
        assertEquals("internet", c.keys().apn());
    }

    @Test
    void parsesFteidFromKeyWithInterfaceTypes() {
        SessionCheckpoint c = cp();
        assertEquals(11, c.sgwS1u().teid());
        assertEquals(0, c.sgwS1u().interfaceType());
        assertEquals("192.0.2.1", c.sgwS1u().address().getHostAddress());
        assertEquals(4, c.sgwS5u().interfaceType());
        assertEquals(5, c.pgwS5u().interfaceType());
        assertEquals("192.0.2.3", c.pgwS5u().address().getHostAddress());
    }

    @Test
    void parseFteidReturnsNullOnBlankOrMalformed() {
        assertNull(SessionCheckpoint.parseFteid(null, 0));
        assertNull(SessionCheckpoint.parseFteid("", 0));
        assertNull(SessionCheckpoint.parseFteid("   ", 0));
        assertNull(SessionCheckpoint.parseFteid("no-at-sign", 0));
        assertNull(SessionCheckpoint.parseFteid("abc@1.2.3.4", 0));
        assertNull(SessionCheckpoint.parseFteid("1@bad-host", 0));
        assertNull(SessionCheckpoint.parseFteid("@1.2.3.4", 0));
    }

    @Test
    void parseFteidHandlesUnsignedTeid() {
        var f = SessionCheckpoint.parseFteid("4294967295@192.0.2.9", 0);
        assertEquals(-1, f.teid()); // 0xFFFFFFFF parsed unsigned -> int -1
    }

    @Test
    void ueResolvesIpv4() {
        assertTrue(cp().ue().ipv4() != null);
        assertEquals("10.64.0.2", cp().ue().ipv4().getHostAddress());
    }

    @Test
    void ueNoneWhenIpv4BlankOrMissing() {
        SessionCheckpoint blank = new SessionCheckpoint(
                "sgw|x", "sgw", "001010000000001", "internet", 7,
                "epc-1", 1L, "", null, null, null, 1L, "1", 1, 1L);
        assertEquals(UeAddresses.none(), blank.ue());
        SessionCheckpoint nullIp = new SessionCheckpoint(
                "sgw|x", "sgw", "001010000000001", "internet", 7,
                "epc-1", 1L, null, null, null, null, 1L, "1", 1, 1L);
        assertEquals(UeAddresses.none(), nullIp.ue());
    }

    @Test
    void nullRequiredFieldsRejected() {
        assertThrows(NullPointerException.class, () ->
                new SessionCheckpoint(null, "sgw", "i", "a", 5, "n", 1L, "", null, null, null, 1L, "1", 1, 1L));
        assertThrows(NullPointerException.class, () ->
                new SessionCheckpoint("s", null, "i", "a", 5, "n", 1L, "", null, null, null, 1L, "1", 1, 1L));
        assertThrows(NullPointerException.class, () ->
                new SessionCheckpoint("s", "sgw", null, "a", 5, "n", 1L, "", null, null, null, 1L, "1", 1, 1L));
        assertThrows(NullPointerException.class, () ->
                new SessionCheckpoint("s", "sgw", "i", null, 5, "n", 1L, "", null, null, null, 1L, "1", 1, 1L));
        assertThrows(NullPointerException.class, () ->
                new SessionCheckpoint("s", "sgw", "i", "a", 5, null, 1L, "", null, null, null, 1L, "1", 1, 1L));
    }
}