package et.elisa.cluster;

import et.elisa.persist.SfClusterNode;
import et.elisa.persist.SfFsPair;
import com.microjainslee.ra.freeswitch.FreeSwitchRaConfig;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * C3 paired topology: this Elisa node → ordered FreeSWITCH ESL targets.
 * Cluster-wide lookup by {@code fsNodeId} is for R9 home-FS remap on S-CSCF
 * failover — not a license for {@link et.elisa.esl.EslFailoverService} to
 * steal another node's FS.
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

    private volatile boolean memoryOnly;
    private volatile List<EslTarget> memoryThisNode;
    private volatile Map<String, EslTarget> memoryByFsNodeId;

    public String nodeId() {
        return nodeId;
    }

    public record EslTarget(String fsNodeId, String host, int port, String password, int priority) {}

    /**
     * Unit-test catalog: this node's pair list plus extra FS nodes (home of
     * another S-CSCF). Never hits Panache / PostgreSQL.
     */
    public FsPairRegistry memoryOnly(String nodeId, List<EslTarget> thisNode, EslTarget... extraCatalog) {
        this.memoryOnly = true;
        this.nodeId = nodeId == null || nodeId.isBlank() ? "elisa-1" : nodeId;
        if (this.fallbackHost == null || this.fallbackHost.isBlank()) {
            this.fallbackHost = "localhost";
        }
        if (this.fallbackPort <= 0) {
            this.fallbackPort = 8021;
        }
        if (this.fallbackPassword == null || this.fallbackPassword.isBlank()) {
            this.fallbackPassword = "ClueCon";
        }
        this.memoryThisNode = thisNode == null ? List.of() : List.copyOf(thisNode);
        Map<String, EslTarget> catalog = new LinkedHashMap<>();
        for (EslTarget t : memoryThisNode) {
            if (t != null && t.fsNodeId() != null) {
                catalog.put(t.fsNodeId(), t);
            }
        }
        if (extraCatalog != null) {
            for (EslTarget t : extraCatalog) {
                if (t != null && t.fsNodeId() != null) {
                    catalog.put(t.fsNodeId(), t);
                }
            }
        }
        this.memoryByFsNodeId = Map.copyOf(catalog);
        return this;
    }

    /** Ordered by priority ascending (lower = preferred). Empty → single fallback from props. */
    public List<EslTarget> targetsForThisNode() {
        if (memoryOnly) {
            List<EslTarget> mem = memoryThisNode;
            if (mem == null || mem.isEmpty()) {
                return List.of(new EslTarget("fallback", fallbackHost, fallbackPort, fallbackPassword, 0));
            }
            return mem;
        }
        List<SfFsPair> pairs = SfFsPair.list("elisaNodeId", nodeId);
        pairs.sort(Comparator.comparingInt(p -> p.priority));
        List<EslTarget> out = new ArrayList<>();
        for (SfFsPair pair : pairs) {
            toTarget(pair.fsNodeId, pair.priority).ifPresent(out::add);
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

    /**
     * Cluster-wide ESL target for a FreeSWITCH node id (checkpoint {@code fsNodeId}).
     * Requires a {@code sf_fs_pair} row so unpublished FS nodes are not bound.
     */
    public Optional<EslTarget> targetByFsNodeId(String fsNodeId) {
        return targetForHome(null, fsNodeId);
    }

    /**
     * Prefer the pair {@code (homeElisaNodeId, fsNodeId)} from PostgreSQL
     * {@code sf_fs_pair}; fall back to any pair row for that FS.
     */
    public Optional<EslTarget> targetForHome(String homeElisaNodeId, String fsNodeId) {
        if (fsNodeId == null || fsNodeId.isBlank()) {
            return Optional.empty();
        }
        if (memoryOnly) {
            Map<String, EslTarget> cat = memoryByFsNodeId;
            return cat == null ? Optional.empty() : Optional.ofNullable(cat.get(fsNodeId));
        }
        if (homeElisaNodeId != null && !homeElisaNodeId.isBlank()) {
            SfFsPair.Pk pk = new SfFsPair.Pk();
            pk.elisaNodeId = homeElisaNodeId;
            pk.fsNodeId = fsNodeId;
            SfFsPair pair = SfFsPair.findById(pk);
            if (pair != null) {
                Optional<EslTarget> hit = toTarget(pair.fsNodeId, pair.priority);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        List<SfFsPair> pairs = SfFsPair.list("fsNodeId", fsNodeId);
        pairs.sort(Comparator.comparingInt(p -> p.priority));
        for (SfFsPair pair : pairs) {
            Optional<EslTarget> hit = toTarget(pair.fsNodeId, pair.priority);
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
    }

    private Optional<EslTarget> toTarget(String fsNodeId, int priority) {
        if (fsNodeId == null || fsNodeId.isBlank()) {
            return Optional.empty();
        }
        SfClusterNode fs = SfClusterNode.findById(fsNodeId);
        if (fs == null || !fs.enabled || !"FS".equalsIgnoreCase(fs.kind)) {
            return Optional.empty();
        }
        ParsedAddr addr = parseAddress(fs.address);
        String password = passwordFromMeta(fs.meta, fallbackPassword);
        return Optional.of(new EslTarget(fs.id, addr.host, addr.port, password, priority));
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
