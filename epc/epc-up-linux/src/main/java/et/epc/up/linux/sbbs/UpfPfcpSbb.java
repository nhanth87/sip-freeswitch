package et.epc.up.linux.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.pfcp.PfcpCodec;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpIes;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.PfcpRules;
import com.microjainslee.ra.pfcp.PfcpSessionState;
import com.microjainslee.ra.pfcp.PfcpUrr;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.PfcpSessionAnswer;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.UeAddresses;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.LinuxGtpCommand;
import et.epc.up.linux.LinuxUrrSpec;
import et.epc.up.linux.LinuxUrrUsage;
import et.epc.up.linux.UpfSessionTable;
import et.epc.up.linux.UrrIpIndex;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Inbound N4 / PFCP session-rule handler. Consumes {@link PfcpMessageEvent}
 * emitted by {@code pfcp-ra} when a remote control plane (SGW-C/PGW-C) sends a
 * Session Establishment/Modification/Deletion Request, keeps the session's PDR/FAR/URR
 * state ({@link PfcpSessionState}), translates each change into Linux GTP kernel recipes via
 * {@link LinuxGtpAgent} and answers the matching PFCP response through {@code pfcp-ra}.
 * The GTP-U device stays out of the JVM.
 */
@SbbAnnotation(name = "UpfPfcpSbb", vendor = "et.epc", version = "1.0")
public abstract class UpfPfcpSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(UpfPfcpSbb.class);

    /** TS 29.244 §8.2.1 causes. */
    static final int CAUSE_SESSION_CONTEXT_NOT_FOUND = 65, CAUSE_RULE_FAILURE = 73;

    private final LinuxGtpAgent agent;
    private final UrrIpIndex ipIndex;
    /** Sessions of this UP, shared by every pooled entity (UpfApp owns one table). */
    private final UpfSessionTable sessions;

    @InjectRa(name = "pfcp-ra")
    private volatile RaCommandPort pfcpRa;

    private volatile List<LinuxGtpCommand> lastCommands = List.of();

    public UpfPfcpSbb() {
        this(null, null);
    }

    public UpfPfcpSbb(LinuxGtpAgent agent) {
        this(agent, null);
    }

    public UpfPfcpSbb(LinuxGtpAgent agent, UrrIpIndex ipIndex) {
        this(agent, ipIndex, new UpfSessionTable());
    }

    public UpfPfcpSbb(LinuxGtpAgent agent, UrrIpIndex ipIndex, UpfSessionTable sessions) {
        this.agent = agent;
        this.ipIndex = ipIndex;
        this.sessions = sessions;
    }

    @CmpField("pfcpSeid")
    public abstract Long getPfcpSeid();

    @CmpField("pfcpSeid")
    public abstract void setPfcpSeid(Long seid);

    public List<LinuxGtpCommand> lastCommands() {
        return lastCommands;
    }

    /** Forwarding tunnels currently installed for a session (tests / status). */
    public List<PfcpRule> tunnels(long seid) {
        PfcpSessionState st = sessions.get(seid);
        return st == null ? List.of() : st.tunnels(seid);
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof PfcpMessageEvent pfcp)) {
            return;
        }
        PfcpMessage msg = pfcp.message();
        long seid = msg.sessionSeid();
        setPfcpSeid(seid);
        switch (msg.type()) {
            case SESSION_ESTABLISHMENT_REQUEST -> establish(msg, seid, pfcp.peer());
            case SESSION_MODIFICATION_REQUEST -> {
                if (agent != null && PfcpCodec.hasQueryUrr(msg.payload())) {
                    respondQueryUrr(msg, pfcp.peer());
                } else {
                    modify(msg, seid, pfcp.peer());
                }
            }
            case SESSION_DELETION_REQUEST -> delete(msg, seid, pfcp.peer());
            default -> LOG.debug("UpfPfcpSbb ignoring {}", msg.type());
        }
    }

    /**
     * Session Establishment (TS 29.244 §7.5.2): the rule IEs become this session's state. An
     * Establishment for a SEID we already hold replaces it (the CP re-established after a
     * failure) instead of installing a second copy of its tunnels.
     */
    private void establish(PfcpMessage req, long seid, InetSocketAddress peer) {
        PfcpSessionState next;
        try {
            next = PfcpSessionState.empty().apply(ruleIes(req));
        } catch (RuntimeException e) {
            LOG.warn("UpfPfcpSbb establishment seid={} rejected: {}", seid, e.toString());
            answer(req, CAUSE_RULE_FAILURE, peer);
            return;
        }
        PfcpSessionState old = sessions.get(seid);
        apply(seid, old == null ? PfcpSessionState.empty() : old, next);
        answer(req, PfcpIe.CAUSE_REQUEST_ACCEPTED, peer);
    }

    /**
     * Session Modification (TS 29.244 §7.5.4): Create / Update / Remove on the existing
     * session — e.g. Update FAR toward a new eNB (Modify Bearer) or to BUFF (Release Access
     * Bearers). Unknown SEID → Cause 65; an Update / Remove of a rule not installed → Cause 73.
     */
    private void modify(PfcpMessage req, long seid, InetSocketAddress peer) {
        PfcpSessionState current = sessions.get(seid);
        if (current == null) {
            answer(req, CAUSE_SESSION_CONTEXT_NOT_FOUND, peer);
            return;
        }
        PfcpSessionState next;
        try {
            next = current.apply(ruleIes(req));
        } catch (RuntimeException e) {
            LOG.warn("UpfPfcpSbb modification seid={} rejected: {}", seid, e.toString());
            answer(req, CAUSE_RULE_FAILURE, peer);
            return;
        }
        apply(seid, current, next);
        answer(req, PfcpIe.CAUSE_REQUEST_ACCEPTED, peer);
    }

    /** Session Deletion (TS 29.244 §7.5.6): tear the tunnels down; unknown SEID → Cause 65. */
    private void delete(PfcpMessage req, long seid, InetSocketAddress peer) {
        PfcpSessionState st = sessions.remove(seid);
        if (st == null) {
            answer(req, CAUSE_SESSION_CONTEXT_NOT_FOUND, peer);
            return;
        }
        List<LinuxGtpCommand> cmds = new ArrayList<>();
        if (agent != null) {
            for (PfcpRule rule : st.tunnels(seid)) {
                cmds.addAll(agent.delete(rule));
                if (rule.urr() != null) {
                    removeUrr(rule);
                }
            }
        }
        lastCommands = List.copyOf(cmds);
        answer(req, PfcpIe.CAUSE_REQUEST_ACCEPTED, peer);
    }

    /**
     * The request's rule IEs. A legacy private rule payload (first octet 1 or 2, from peers that
     * predate the TS 29.244 encoding) is mapped onto the same Create PDR / FAR IEs.
     */
    private static List<PfcpIe> ruleIes(PfcpMessage req) {
        byte[] p = req.payload();
        if (p.length > 0 && p[0] != 0) {
            PfcpRules legacy = PfcpCodec.decodeRules(req.seid(), p);
            return PfcpIes.fromRules(legacy.rules(), legacy.ueIpv4());
        }
        return req.ies();
    }

    /** Kernel recipes for the difference between two states: removed tunnels first, then new ones. */
    private void apply(long seid, PfcpSessionState from, PfcpSessionState to) {
        List<PfcpRule> before = from.tunnels(seid);
        List<PfcpRule> after = to.tunnels(seid);
        UeAddresses ue = toUe(to.ueIpv4());
        List<LinuxGtpCommand> cmds = new ArrayList<>();
        List<String> urrIds = new ArrayList<>(sessions.urrs(seid));
        if (agent != null) {
            for (PfcpRule old : before) {
                if (after.contains(old)) {
                    continue;
                }
                cmds.addAll(agent.delete(old));
                if (old.urr() != null && after.stream().noneMatch(r -> old.urr().equals(r.urr()))) {
                    removeUrr(old);
                    urrIds.remove(String.valueOf(old.urr().urrId()));
                }
            }
            for (PfcpRule rule : after) {
                if (before.contains(rule)) {
                    continue;
                }
                cmds.addAll(agent.install(rule, ue));
                if (rule.urr() != null && !urrIds.contains(String.valueOf(rule.urr().urrId()))) {
                    urrIds.add(registerUrr(seid, rule, ue));
                }
            }
        }
        sessions.urrs(seid, urrIds);
        sessions.put(seid, to);
        lastCommands = List.copyOf(cmds);
        LOG.info("UpfPfcpSbb seid={} tunnels {}->{} urrs={} commands={}", seid, before.size(), after.size(),
                urrIds.size(), cmds.size());
    }

    /** URR lifecycle for one installed rule: counter in the registry + UE-IP attribution. */
    private String registerUrr(long seid, PfcpRule rule, UeAddresses ue) {
        PfcpUrr urr = rule.urr();
        String urrId = String.valueOf(urr.urrId());
        agent.urr().create(urrId, new LinuxUrrSpec(seid, urr.reportingTriggers(),
                urr.volumeThresholdBytes(), urr.timeThresholdSeconds(), rule.uplink()));
        UrrIpIndex idx = ipIndex;
        if (idx != null && ue != null && ue.ipv4() != null) {
            idx.register(urrId, ue.ipv4());
        }
        return urrId;
    }

    private void removeUrr(PfcpRule rule) {
        String urrId = String.valueOf(rule.urr().urrId());
        agent.urr().remove(urrId);
        UrrIpIndex idx = ipIndex;
        if (idx != null) {
            idx.unregister(urrId);
        }
    }

    /**
     * Query-URR (TS 29.244 §7.5.4.10): on-demand counter snapshot for this session's URRs,
     * answered as Usage Report IEs in the Session Modification Response. Read-only — the
     * report baseline does not advance.
     */
    private void respondQueryUrr(PfcpMessage req, InetSocketAddress peer) {
        List<String> urrIds = sessions.urrs(req.seid());
        List<PfcpUsageReport> reports = new ArrayList<>(urrIds.size());
        for (String urrId : urrIds) {
            Optional<LinuxUrrUsage> usage = agent.readCounter(urrId);
            if (usage.isEmpty()) {
                continue;
            }
            boolean uplink = agent.urr().spec(urrId)
                    .map(LinuxUrrSpec::uplink)
                    .orElse(true);
            long volume = usage.get().deltaBytes();
            // Query snapshot: no immediate-report bit in the trigger set, report as PERIO.
            int trigger = PfcpIe.UT_PERIO;
            reports.add(uplink
                    ? new PfcpUsageReport(Integer.parseInt(urrId), trigger, volume, 0L, 0)
                    : new PfcpUsageReport(Integer.parseInt(urrId), trigger, 0L, volume, 0));
        }
        RaCommandPort port = pfcpRa;
        if (port == null) {
            LOG.debug("UpfPfcpSbb pfcp-ra not injected; no Query-URR response sent");
            return;
        }
        // §7.5.5.1: Cause + Usage Report (Session Modification Response) IEs.
        port.sendCommand(new PfcpSessionAnswer(peer, req, PfcpIe.CAUSE_REQUEST_ACCEPTED,
                PfcpCodec.decodeIes(PfcpCodec.encodeUsageReports(reports, PfcpIe.USAGE_REPORT_SMR))));
    }

    private static UeAddresses toUe(InetAddress ipv4) {
        return ipv4 instanceof Inet4Address v4 ? new UeAddresses(v4, null) : UeAddresses.none();
    }

    /** Response with Cause (+ Node ID / UP F-SEID on an Establishment) built by pfcp-ra. */
    private void answer(PfcpMessage req, int cause, InetSocketAddress peer) {
        RaCommandPort port = pfcpRa;
        if (port == null) {
            LOG.debug("UpfPfcpSbb pfcp-ra not injected; no response sent");
            return;
        }
        port.sendCommand(new PfcpSessionAnswer(peer, req, cause));
    }

    public static final class $Concrete extends UpfPfcpSbb {

        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(LinuxGtpAgent agent) {
            super(agent);
        }

        public $Concrete(LinuxGtpAgent agent, UrrIpIndex ipIndex) {
            super(agent, ipIndex);
        }

        public $Concrete(LinuxGtpAgent agent, UrrIpIndex ipIndex, UpfSessionTable sessions) {
            super(agent, ipIndex, sessions);
        }

        @Override public Long getPfcpSeid() { return lng("pfcpSeid"); }
        @Override public void setPfcpSeid(Long v) { put("pfcpSeid", v); write("setPfcpSeid", Long.class, v); }

        private Long lng(String key) {
            Object v = local.get(key);
            return v instanceof Long l ? l : null;
        }

        private void put(String key, Object value) {
            if (value == null) {
                local.remove(key);
            } else {
                local.put(key, value);
            }
        }

        private void write(String setter, Class<?> type, Object value) {
            try {
                cmpWrite(method(setter, type), value);
            } catch (IllegalStateException ignored) {
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return UpfPfcpSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
