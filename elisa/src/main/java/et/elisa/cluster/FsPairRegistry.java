package et.elisa.cluster;

import et.elisa.persist.SfClusterNode;
import et.elisa.persist.SfFsPair;
import et.elisa.ra.freeswitch.FreeSwitchRaConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * C3 paired topology: this Elisa node → ordered FreeSWITCH ESL targets.
 */
@ApplicationScoped
public class FsPairRegistry {

    private static final Logger LOG = Logger.getLogger(FsPairRegistry.class);

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String nodeId;

    @ConfigProperty(name = "sf.esl.host", defaultValue = "localhost")
    String fallbackHost;

    @ConfigProperty(name = "sf.esl.port", defaultValue = "8021")
    int fallbackPort;

    @ConfigProperty(name = "sf.esl.password", defaultValue = "ClueCon")
    String fallbackPassword;

    public String nodeId() {
        return nodeId;
    }

    public record EslTarget(String fsNodeId, String host, int port, String password, int priority) {}

    /** Ordered by priority ascending (lower = preferred). Empty → single fallback from props. */
    public List<EslTarget> targetsForThisNode() {
        List<SfFsPair> pairs = SfFsPair.list("elisaNodeId", nodeId);
        pairs.sort(Comparator.comparingInt(p -> p.priority));
        List<EslTarget> out = new ArrayList<>();
        for (SfFsPair pair : pairs) {
            SfClusterNode fs = SfClusterNode.findById(pair.fsNodeId);
            if (fs == null || !fs.enabled || !"FS".equalsIgnoreCase(fs.kind)) {
                continue;
            }
            ParsedAddr addr = parseAddress(fs.address);
            String password = passwordFromMeta(fs.meta, fallbackPassword);
            out.add(new EslTarget(fs.id, addr.host, addr.port, password, pair.priority));
        }
        if (out.isEmpty()) {
            out.add(new EslTarget("fallback", fallbackHost, fallbackPort, fallbackPassword, 0));
            LOG.debugf("fs_pair empty for node=%s — using props %s:%d", nodeId, fallbackHost, fallbackPort);
        }
        return out;
    }

    public Optional<EslTarget> primary() {
        List<EslTarget> t = targetsForThisNode();
        return t.isEmpty() ? Optional.empty() : Optional.of(t.getFirst());
    }

    public FreeSwitchRaConfig toConfig(EslTarget target) {
        FreeSwitchRaConfig cfg = new FreeSwitchRaConfig();
        cfg.setHost(target.host());
        cfg.setPort(target.port());
        cfg.setPassword(target.password());
        cfg.setAutoSubscribe(true);
        return cfg;
    }

    private static ParsedAddr parseAddress(String address) {
        if (address == null || address.isBlank()) {
            return new ParsedAddr("127.0.0.1", 8021);
        }
        String s = address.trim();
        if (s.startsWith("esl://")) {
            s = s.substring(6);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            try {
                return new ParsedAddr(s.substring(0, colon), Integer.parseInt(s.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return new ParsedAddr(s, 8021);
    }

    private static String passwordFromMeta(String meta, String fallback) {
        if (meta == null || meta.isBlank() || "{}".equals(meta.trim())) {
            return fallback;
        }
        // minimal JSON extract "password":"..."
        int i = meta.indexOf("\"password\"");
        if (i < 0) {
            return fallback;
        }
        int colon = meta.indexOf(':', i);
        int q1 = meta.indexOf('"', colon + 1);
        int q2 = meta.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return fallback;
        }
        return meta.substring(q1 + 1, q2);
    }

    private record ParsedAddr(String host, int port) {}
}
