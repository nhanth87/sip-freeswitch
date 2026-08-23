package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import et.epc.api.UeAddresses;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owned user-plane baseline: Linux kernel GTP. Emits ip/gtp/tc/route recipes for an
 * operator or privileged helper to apply. Never opens a GTP-U socket in Java.
 */
public final class LinuxGtpAgent {

    private static final String QER_QDISC_HANDLE = "1:";
    private static final String QER_UL_CLASS = "1:10";
    private static final String QER_DL_CLASS = "1:11";
    private static final String QER_DEFAULT_PRIO = "30";

    private final String device;
    private final LinuxUrrRegistry urr;
    private final LinuxDownlinkBuffer downlinkBuffer;
    private volatile boolean qerEnabled = true;

    public LinuxGtpAgent(String device) {
        this(device, new HeapUrrDatastore(), 0L);
    }

    public LinuxGtpAgent(String device, UrrDatastore urrDatastore) {
        this(device, urrDatastore, 0L);
    }

    /**
     * @param baselinePersistMs URR baseline persistence throttle (epc.up.urr.baseline.persist-ms);
     *        {@code 0} persists on every poll.
     */
    public LinuxGtpAgent(String device, UrrDatastore urrDatastore, long baselinePersistMs) {
        this.device = Objects.requireNonNull(device);
        this.urr = new LinuxUrrRegistry(Objects.requireNonNull(urrDatastore, "urrDatastore"),
                baselinePersistMs);
        this.downlinkBuffer = new LinuxDownlinkBuffer();
    }

    /** QER recipe gate (epc.up.qer.enabled). Default on for direct callers; host wiring sets it from config. */
    public LinuxGtpAgent withQerEnabled(boolean enabled) {
        this.qerEnabled = enabled;
        return this;
    }

    public boolean qerEnabled() {
        return qerEnabled;
    }

    public String device() {
        return device;
    }

    /** URR counter model + delta poller used for PFCP Query-URR and usage reporting. */
    public LinuxUrrRegistry urr() {
        return urr;
    }

    /** On-demand cumulative read for PFCP Query-URR, without advancing the report baseline. */
    public Optional<LinuxUrrUsage> readCounter(String urrId) {
        return urr.readCounter(urrId);
    }

    /** Placeholder downlink buffering queue used before an Update-FAR lands. */
    public LinuxDownlinkBuffer downlinkBuffer() {
        return downlinkBuffer;
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
/**
     * QER rate-limiting: one HTB root qdisc plus per-direction (UL/DL) HTB classes and
     * u32 filters matching the UE IP. MBR is the ceiling, GBR is the guaranteed rate.
     * Recipes are emitted as tc argv for a privileged helper; no tc ioctl happens here.
     */
    public List<LinuxGtpCommand> qer(PfcpRule rule, UeAddresses ue, LinuxQer qer) {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(qer, "qer");
        if (!qerEnabled) {
            return List.of();
        }
        if (!Objects.equals(rule.qerId(), qer.qerId())) {
            throw new IllegalArgumentException(
                    "PfcpRule qerId=" + rule.qerId() + " but LinuxQer qerId=" + qer.qerId());
        }
        if (ue == null || ue.ipv4() == null) {
            return List.of(); // nothing to rate-limit without a UE IP
        }
        String ip = ue.ipv4().getHostAddress();
        List<LinuxGtpCommand> out = new ArrayList<>();
        out.add(new LinuxGtpCommand("qer-qdisc", List.of(
                "tc", "qdisc", "add", "dev", device, "root",
                "handle", QER_QDISC_HANDLE, "htb", "default", QER_DEFAULT_PRIO)));
        out.add(new LinuxGtpCommand("qer-class-ul", List.of(
                "tc", "class", "add", "dev", device,
                "parent", QER_QDISC_HANDLE, "classid", QER_UL_CLASS,
                "htb", "rate", rate(qer.gbrUlBps()), "ceil", rate(qer.mbrUlBps()))));
        out.add(new LinuxGtpCommand("qer-class-dl", List.of(
                "tc", "class", "add", "dev", device,
                "parent", QER_QDISC_HANDLE, "classid", QER_DL_CLASS,
                "htb", "rate", rate(qer.gbrDlBps()), "ceil", rate(qer.mbrDlBps()))));
        out.add(new LinuxGtpCommand("qer-filter-ul", List.of(
                "tc", "filter", "add", "dev", device,
                "parent", QER_QDISC_HANDLE, "protocol", "ip", "prio", "1",
                "u32", "match", "ip", "src", ip, "flowid", QER_UL_CLASS)));
        out.add(new LinuxGtpCommand("qer-filter-dl", List.of(
                "tc", "filter", "add", "dev", device,
                "parent", QER_QDISC_HANDLE, "protocol", "ip", "prio", "1",
                "u32", "match", "ip", "dst", ip, "flowid", QER_DL_CLASS)));
        return List.copyOf(out);
    }

    /** Teardown placeholder: remove the per-session u32 filters and HTB classes only. */
    public List<LinuxGtpCommand> qerDelete(PfcpRule rule, UeAddresses ue) {
        Objects.requireNonNull(rule, "rule");
        if (ue == null || ue.ipv4() == null) {
            return List.of();
        }
        return List.of(
                new LinuxGtpCommand("qer-del-filter", List.of(
                        "tc", "filter", "del", "dev", device,
                        "parent", QER_QDISC_HANDLE, "prio", "1")),
                new LinuxGtpCommand("qer-del-class-ul", List.of(
                        "tc", "class", "del", "dev", device, "classid", QER_UL_CLASS)),
                new LinuxGtpCommand("qer-del-class-dl", List.of(
                        "tc", "class", "del", "dev", device, "classid", QER_DL_CLASS)));
    }
/**
     * SGW-U two-NIC bridge: two gtp devices (access/core) with per-interface fwmark
     * policy routing instead of two same-destination routes sharing one table.
     */
    public List<LinuxGtpCommand> sgwBridge(LinuxSgwBridge bridge) {
        Objects.requireNonNull(bridge, "bridge");
        String accessMark = fwmark(bridge.accessMark());
        String coreMark = fwmark(bridge.coreMark());
        List<LinuxGtpCommand> out = new ArrayList<>();
        out.add(new LinuxGtpCommand("ensure-gtp-access", List.of(
                "ip", "link", "add", bridge.accessDevice(), "type", "gtp", "role", "sgsn")));
        out.add(new LinuxGtpCommand("ensure-gtp-core", List.of(
                "ip", "link", "add", bridge.coreDevice(), "type", "gtp", "role", "sgsn")));
        out.add(new LinuxGtpCommand("ip-rule-access", List.of(
                "ip", "rule", "add", "fwmark", accessMark, "lookup", bridge.accessTable())));
        out.add(new LinuxGtpCommand("ip-rule-core", List.of(
                "ip", "rule", "add", "fwmark", coreMark, "lookup", bridge.coreTable())));
        out.add(new LinuxGtpCommand("route-access", List.of(
                "ip", "route", "add", bridge.accessRoute(),
                "dev", bridge.accessDevice(), "table", bridge.accessTable())));
        out.add(new LinuxGtpCommand("route-core", List.of(
                "ip", "route", "add", bridge.coreRoute(),
                "dev", bridge.coreDevice(), "table", bridge.coreTable())));
        out.add(new LinuxGtpCommand("mark-access", List.of(
                "iptables", "-t", "mangle", "-A", "PREROUTING",
                "-i", bridge.accessDevice(), "-j", "MARK", "--set-mark", accessMark)));
        out.add(new LinuxGtpCommand("mark-core", List.of(
                "iptables", "-t", "mangle", "-A", "PREROUTING",
                "-i", bridge.coreDevice(), "-j", "MARK", "--set-mark", coreMark)));
        return List.copyOf(out);
    }

    /**
     * BUFF apply action. Returns no kernel recipe: before an Update-FAR installs the
     * downlink FAR, the rule descriptor is queued in {@link #downlinkBuffer()} and
     * drained by the FAR update path. Real GTP-U buffering stays outside the JVM.
     */
    public List<LinuxGtpCommand> bufferApplyAction(PfcpRule dlRule) {
        Objects.requireNonNull(dlRule, "dlRule");
        downlinkBuffer.enqueue(dlRule.seid(), dlRule);
        return List.of();
    }

    private static String rate(long bps) {
        return Long.toUnsignedString(bps) + "bit";
    }

    private static String fwmark(int mark) {
        return "0x" + Integer.toHexString(mark);
    }
}
