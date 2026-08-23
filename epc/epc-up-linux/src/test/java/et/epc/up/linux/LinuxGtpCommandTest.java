package et.epc.up.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxGtpCommandTest {

    @Test
    void recordHoldsDescriptionAndArgv() {
        LinuxGtpCommand c = new LinuxGtpCommand("ensure", List.of("ip", "link"));
        assertEquals("ensure", c.description());
        assertEquals(List.of("ip", "link"), c.argv());
    }

    @Test
    void nullDescriptionRejected() {
        assertThrows(NullPointerException.class, () -> new LinuxGtpCommand(null, List.of()));
    }

    @Test
    void argvIsCopiedAndImmutable() {
        LinuxGtpCommand c = new LinuxGtpCommand("x", List.of("a"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> c.argv().add("b"));
        assertFalse(c.argv().isEmpty());
    }
}