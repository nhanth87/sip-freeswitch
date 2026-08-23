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
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpMessageType;
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.PfcpRules;
import com.microjainslee.ra.pfcp.PfcpUrr;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.UeAddresses;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.LinuxGtpCommand;
import et.epc.up.linux.LinuxUrrSpec;
import et.epc.up.linux.LinuxUrrUsage;
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
 * Session Establishment/Modification/Deletion Request, decodes the PDR/FAR/QER/URR
 * rule payload, translates it into Linux GTP kernel recipes via {@link LinuxGtpAgent},
 * and answers the matching PFCP response. The GTP-U device stays out of the JVM.
 */
@SbbAnnotation(name = "UpfPfcpSbb", vendor = "et.epc", version = "1.0")
public abstract class UpfPfcpSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(UpfPfcpSbb.class);

    private final LinuxGtpAgent agent;
    private final UrrIpIndex ipIndex;
    private final Map<Long, List<PfcpRule>> installed = new ConcurrentHashMap<>();
    /** URR ids (decimal string form) installed per session, for Query-URR snapshots. */
    private final Map<Long, List<String>> urrsBySeid = new ConcurrentHashMap<>();

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
        this.agent = agent;
        this.ipIndex = ipIndex;
    }

    @CmpField("pfcpSeid")
    public abstract Long getPfcpSeid();

    @CmpField("pfcpSeid")
    public abstract void setPfcpSeid(Long seid);

    public List<LinuxGtpCommand> lastCommands() {
        return lastCommands;
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
        PfcpRules rules = PfcpCodec.decodeRules(msg.payload());
        setPfcpSeid(msg.seid());
        switch (msg.type()) {
            case SESSION_ESTABLISHMENT_REQUEST -> install(msg, rules, pfcp.peer());
            case SESSION_MODIFICATION_REQUEST -> {
                if (agent != null && PfcpCodec.hasQueryUrr(msg.payload())) {
                    respondQueryUrr(msg, pfcp.peer());
                } else {
                    install(msg, rules, pfcp.peer());
                }
            }
            case SESSION_DELETION_REQUEST -> delete(msg, pfcp.peer());
            default -> LOG.debug("UpfPfcpSbb ignoring {}", msg.type());
        }
    }

    private void install(PfcpMessage req, PfcpRules rules, InetSocketAddress peer) {
        if (agent == null) {
            respond(req, responseOf(req.type()), peer);
            return;
        }
        UeAddresses ue = toUe(rules.ueIpv4());
        List<LinuxGtpCommand> cmds = new ArrayList<>();
        List<String> urrIds = new ArrayList<>();
        for (PfcpRule rule : rules.rules()) {
            cmds.addAll(agent.install(rule, ue));
            if (rule.urr() != null) {
                urrIds.add(registerUrr(req.seid(), rule, ue));
            }
        }
        if (!urrIds.isEmpty()) {
            urrsBySeid.put(req.seid(), List.copyOf(urrIds));
        }
        lastCommands = List.copyOf(cmds);
        installed.put(req.seid(), List.copyOf(rules.rules()));
        LOG.info("UpfPfcpSbb install seid={} rules={} urrs={} commands={}", req.seid(),
                rules.rules().size(), urrIds.size(), cmds.size());
        respond(req, responseOf(req.type()), peer);
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

    private void delete(PfcpMessage req, InetSocketAddress peer) {
        List<PfcpRule> rules = installed.remove(req.seid());
        urrsBySeid.remove(req.seid());
        List<LinuxGtpCommand> cmds = new ArrayList<>();
        if (agent != null && rules != null) {
            for (PfcpRule rule : rules) {
                cmds.addAll(agent.delete(rule));
                if (rule.urr() != null) {
                    removeUrr(rule);
                }
            }
        }
        lastCommands = List.copyOf(cmds);
        respond(req, responseOf(req.type()), peer);
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
     * Query-URR (TS 29.244): on-demand counter snapshot for this session's URRs,
     * answered in the Session Modification Response payload. Read-only — the
     * report baseline does not advance.
     */
    private void respondQueryUrr(PfcpMessage req, InetSocketAddress peer) {
        List<String> urrIds = urrsBySeid.getOrDefault(req.seid(), List.of());
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
        port.sendCommand(new SendPfcpMessage(new PfcpMessage(
                PfcpMessageType.SESSION_MODIFICATION_RESPONSE, req.seid(), req.sequence(),
                PfcpCodec.encodeUsageReports(reports)), peer));
    }

    private static UeAddresses toUe(InetAddress ipv4) {
        return ipv4 instanceof Inet4Address v4 ? new UeAddresses(v4, null) : UeAddresses.none();
    }

    private static PfcpMessageType responseOf(PfcpMessageType request) {
        return switch (request) {
            case SESSION_ESTABLISHMENT_REQUEST -> PfcpMessageType.SESSION_ESTABLISHMENT_RESPONSE;
            case SESSION_MODIFICATION_REQUEST -> PfcpMessageType.SESSION_MODIFICATION_RESPONSE;
            default -> PfcpMessageType.SESSION_DELETION_RESPONSE;
        };
    }

    private void respond(PfcpMessage req, PfcpMessageType type, InetSocketAddress peer) {
        RaCommandPort port = pfcpRa;
        if (port == null) {
            LOG.debug("UpfPfcpSbb pfcp-ra not injected; no response sent");
            return;
        }
        port.sendCommand(new SendPfcpMessage(
                new PfcpMessage(type, req.seid(), req.sequence(), new byte[0]), peer));
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