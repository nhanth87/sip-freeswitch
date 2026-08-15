package et.epc.up.linux;

import java.util.List;
import java.util.Objects;

/** One shell/netlink recipe. The JVM never forwards GTP-U. */
public record LinuxGtpCommand(String description, List<String> argv) {
    public LinuxGtpCommand {
        Objects.requireNonNull(description);
        argv = List.copyOf(argv);
    }
}
