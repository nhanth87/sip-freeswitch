package et.epc.up.linux.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import et.epc.api.UeAddresses;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.LinuxGtpCommand;
import et.epc.up.linux.events.UpfProgramSessionEvent;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * N4 / PFCP user-plane rule programming SBB. Owns Linux GTP recipe emission for
 * the kernel GTP-U device (which stays non-JVM) and, when {@code pfcp-ra} is
 * injected, pushes the matching {@link PfcpProgramSession} back toward the UPF
 * control plane. CMP holds the in-flight PFCP SEID correlation.
 */
@SbbAnnotation(name = "UpfSbb", vendor = "et.epc", version = "1.0")
public abstract class UpfSbb extends CmpBackedSbb implements SleeEventHandler {

    /** Convention used when deriving the UPF N4 control-plane peer. */
    static final int N4_PORT = 8805;

    private static final Logger LOG = LogManager.getLogger(UpfSbb.class);

    private final LinuxGtpAgent agent;

    @InjectRa(name = "pfcp-ra")
    private volatile RaCommandPort pfcpRa;

    private volatile List<LinuxGtpCommand> lastCommands = List.of();

    public UpfSbb() {
        this(null);
    }

    public UpfSbb(LinuxGtpAgent agent) {
        this.agent = agent;
    }

    @CmpField("pfcpSeid")
    public abstract Long getPfcpSeid();

    @CmpField("pfcpSeid")
    public abstract void setPfcpSeid(Long seid);

    /** Last Linux GTP recipes emitted by {@link #onEvent}; used by tests and ops tooling. */
    public List<LinuxGtpCommand> lastCommands() {
        return lastCommands;
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (!(event instanceof UpfProgramSessionEvent upf)) {
            return;
        }
        setPfcpSeid(upf.getSeid());
        boolean install = upf.getUe() != null;
        List<LinuxGtpCommand> cmds = agent == null
                ? List.of()
                : (install ? agent.install(upf.getRule(), upf.getUe()) : agent.delete(upf.getRule()));
        lastCommands = List.copyOf(cmds);
        LOG.info("UpfSbb {} seid={} emitted={} commands", install ? "install" : "delete",
                upf.getSeid(), cmds.size());
        pushPfcp(upf, install);
    }

    private void pushPfcp(UpfProgramSessionEvent upf, boolean install) {
        RaCommandPort port = pfcpRa;
        if (port == null) {
            LOG.debug("UpfSbb pfcp-ra not injected; Linux GTP recipes emitted only");
            return;
        }
        PfcpRule rule = upf.getRule();
        InetAddress ueIpv4 = upf.getUe() != null ? upf.getUe().ipv4() : null;
        port.sendCommand(new PfcpProgramSession(upfOf(rule), upf.getSeid(),
                install ? List.of(rule) : List.of(), ueIpv4));
    }

    private static InetSocketAddress upfOf(PfcpRule rule) {
        return new InetSocketAddress(rule.remote().address(), N4_PORT);
    }

    public static final class $Concrete extends UpfSbb {

        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(LinuxGtpAgent agent) {
            super(agent);
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
                // local map when the container CMP store is unbound
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return UpfSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}