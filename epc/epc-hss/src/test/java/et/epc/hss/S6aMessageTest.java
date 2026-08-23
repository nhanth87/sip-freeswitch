package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class S6aMessageTest {

    @Test
    void builderRoundTrip() {
        S6aMessage m = S6aMessage.builder(S6aCommand.AIR, true)
                .putUtf8(S6aAvp.USER_NAME, "001010000000001")
                .put(S6aAvp.RAND, new byte[] {1, 2})
                .build();
        assertTrue(m.request());
        assertEquals(S6aCommand.AIR, m.command());
        assertEquals("001010000000001", m.avpUtf8(S6aAvp.USER_NAME));
        assertArrayEquals(new byte[] {1, 2}, m.avp(S6aAvp.RAND));
    }

    @Test
    void avpUtf8MissingReturnsNull() {
        S6aMessage m = S6aMessage.builder(S6aCommand.AIR, true).build();
        assertNull(m.avpUtf8(S6aAvp.USER_NAME));
        assertNull(m.avp(S6aAvp.RAND));
    }

    @Test
    void nullAvpsBecomesEmpty() {
        S6aMessage m = new S6aMessage(S6aCommand.ULA, false, null);
        assertTrue(m.avps().isEmpty());
    }

    @Test
    void avpsAreUnmodifiable() {
        S6aMessage m = S6aMessage.builder(S6aCommand.AIR, true).build();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> m.avps().put(1, new byte[] {1}));
    }

    @Test
    void nullCommandRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new S6aMessage(null, true, null));
    }

    @Test
    void requestFlagIndependentOfCommand() {
        S6aMessage req = S6aMessage.builder(S6aCommand.AIR, true).build();
        S6aMessage ans = S6aMessage.builder(S6aCommand.AIA, false).build();
        assertTrue(req.request());
        assertFalse(ans.request());
    }
}