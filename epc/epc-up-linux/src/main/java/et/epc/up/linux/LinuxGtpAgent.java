package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import et.epc.api.UeAddresses;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Owned user-plane baseline: Linux kernel GTP. Emits ip/gtp recipes for an
 * operator or privileged helper to apply. Never opens a GTP-U socket in Java.
 */
public final class LinuxGtpAgent {

    private final String device;

    public LinuxGtpAgent(String device) {
        this.device = Objects.requireNonNull(device);
    }

    public List<LinuxGtpCommand> install(PfcpRule rule, UeAddresses ue) {
        Objects.requireNonNull(rule);
        List<LinuxGtpCommand> out = new ArrayList<>();
        out.add(new LinuxGtpCommand("ensure-gtp-device", List.of(
                "ip", "link", "add", device, "type", "gtp", "role", "sgsn")));
        PfcpFteid local = rule.local();
        PfcpFteid remote = rule.remote();
        out.add(new LinuxGtpCommand("add-tunnel", List.of(
                "gtp-tunnel", "add", device,
                "v0", Integer.toUnsignedString(local.teid()),
                remote.address().getHostAddress(),
                Integer.toUnsignedString(remote.teid()))));
        if (ue != null && ue.ipv4() != null) {
            out.add(new LinuxGtpCommand("anti-spoof", List.of(
                    "iptables", "-A", "FORWARD", "-i", device,
                    "-s", ue.ipv4().getHostAddress(), "-j", "ACCEPT")));
        }
        return List.copyOf(out);
    }

    public List<LinuxGtpCommand> delete(PfcpRule rule) {
        return List.of(new LinuxGtpCommand("del-tunnel", List.of(
                "gtp-tunnel", "del", device,
                Integer.toUnsignedString(rule.local().teid()))));
    }
}
