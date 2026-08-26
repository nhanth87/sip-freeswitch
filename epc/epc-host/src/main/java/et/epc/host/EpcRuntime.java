package et.epc.host;

import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.cluster.ClusterMode;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.hss.MmeAllowlist;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.InetSocketAddress;
import java.util.Optional;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EpcRuntime {

    private static final Logger LOG = LogManager.getLogger(EpcRuntime.class);

    @ConfigProperty(name = "epc.role.hss", defaultValue = "true")
    boolean roleHss;

    @ConfigProperty(name = "epc.role.sgwc", defaultValue = "true")
    boolean roleSgwc;

    @ConfigProperty(name = "epc.role.pgwc", defaultValue = "true")
    boolean rolePgwc;

    @ConfigProperty(name = "epc.role.pcrf", defaultValue = "false")
    boolean rolePcrf;

    @ConfigProperty(name = "epc.role.pcef", defaultValue = "false")
    boolean rolePcef;

    @ConfigProperty(name = "epc.role.up", defaultValue = "false")
    boolean roleUp;

    @ConfigProperty(name = "epc.cluster.node-id", defaultValue = "epc-1")
    String nodeId;

    @ConfigProperty(name = "epc.cluster.mode", defaultValue = "LOCAL")
    String mode;

    @ConfigProperty(name = "epc.cluster.name", defaultValue = "epc-cluster")
    String clusterName;

    @ConfigProperty(name = "epc.cluster.node-index", defaultValue = "1")
    int nodeIndex;

    @ConfigProperty(name = "epc.gtp.peer.host", defaultValue = "127.0.0.1")
    String gtpPeerHost;

    @ConfigProperty(name = "epc.gtp.peer.port", defaultValue = "2123")
    int gtpPeerPort;

    @ConfigProperty(name = "epc.pfcp.peer.host", defaultValue = "127.0.0.1")
    String pfcpPeerHost;

    @ConfigProperty(name = "epc.pfcp.peer.port", defaultValue = "8805")
    int pfcpPeerPort;

    @ConfigProperty(name = "epc.gtp.bind.port", defaultValue = "2123")
    int gtpBindPort;

    @ConfigProperty(name = "epc.pfcp.bind.port", defaultValue = "8805")
    int pfcpBindPort;

    @ConfigProperty(name = "epc.s5c.wire", defaultValue = "false")
    boolean s5cWire;

    @ConfigProperty(name = "epc.pco.pcscf-ipv4", defaultValue = "")
    java.util.Optional<String> pcoPcscfIpv4;

    @ConfigProperty(name = "epc.pco.dns-ipv4", defaultValue = "")
    java.util.Optional<String> pcoDnsIpv4;

    @ConfigProperty(name = "epc.s5c.pgwc.peer.host", defaultValue = "127.0.0.1")
    String s5cPgwcPeerHost;

    @ConfigProperty(name = "epc.s5c.pgwc.peer.port", defaultValue = "2123")
    int s5cPgwcPeerPort;

    @ConfigProperty(name = "epc.diameter.peer.host")
    Optional<String> diameterPeerHost;

    @ConfigProperty(name = "epc.diameter.peer.port", defaultValue = "3868")
    int diameterPeerPort;

    @ConfigProperty(name = "epc.diameter.peer.role", defaultValue = "client")
    String diameterPeerRole;

    @ConfigProperty(name = "epc.diameter.destination.host")
    Optional<String> diameterDestHost;

    @ConfigProperty(name = "epc.diameter.destination.realm")
    Optional<String> diameterDestRealm;

    @ConfigProperty(name = "epc.diameter.origin.host")
    Optional<String> diameterOriginHost;

    @ConfigProperty(name = "epc.diameter.origin.realm", defaultValue = "epc.lab")
    String diameterOriginRealm;

    @ConfigProperty(name = "epc.jdbc.url", defaultValue = EpcJdbc.DEFAULT_URL)
    String jdbcUrl;

    @ConfigProperty(name = "epc.datastore.enabled", defaultValue = "true")
    boolean datastoreEnabled;

    @ConfigProperty(name = "epc.hss.secret.kek-hex", defaultValue = "000102030405060708090a0b0c0d0e0f")
    String kekHex;

    @ConfigProperty(name = "epc.hss.mme.allowlist")
    Optional<String> mmeAllowlist;

    @ConfigProperty(name = "epc.hss.mme.allowlist.enabled", defaultValue = "false")
    boolean mmeAllowlistEnabled;

    // UP dataplane (URR charging loop + QER gate) — consumed by the UP role wiring.
    @ConfigProperty(name = "epc.up.qer.enabled", defaultValue = "false")
    boolean upQerEnabled;

    @ConfigProperty(name = "epc.up.urr.mode", defaultValue = "poll")
    String upUrrMode;

    @ConfigProperty(name = "epc.up.urr.poll-ms", defaultValue = "60000")
    long upUrrPollMs;

    @ConfigProperty(name = "epc.up.urr.baseline.persist-ms", defaultValue = "300000")
    long upUrrBaselinePersistMs;

    @Inject
    @io.quarkus.agroal.DataSource("epc")
    DataSource dataSource;

    @Inject
    EpcInfinispanManager ispn;

    private EpcBootstrap bootstrap;
    private LinkStatusBoard board;
    private MicroSleeContainer slee;

    void onStart(@Observes StartupEvent ev) {
        EpcJdbc.requirePostgres(jdbcUrl);
        et.epc.api.PcoDefaults.configure(pcoPcscfIpv4.orElse(null), pcoDnsIpv4.orElse(null));
        EpcRoles roles = EpcRoles.fromEnv(roleHss, roleSgwc, rolePgwc, rolePcrf, rolePcef, roleUp);
        EpcClusterConfig cfg = new EpcClusterConfig(
                nodeId, ClusterMode.valueOf(mode), clusterName, nodeIndex);
        slee = new MicroSleeContainer();
        bootstrap = new EpcBootstrap(roles, cfg).withJdbcUrl(jdbcUrl)
                .withDiameter(new DiameterSeam(diameterPeerHost.orElse(null), diameterPeerPort,
                        diameterPeerRole, diameterDestHost.orElse(null),
                        diameterDestRealm.orElse(null), diameterOriginHost.orElse(null),
                        diameterOriginRealm))
                .withMmeAllowlist(MmeAllowlist.parse(mmeAllowlistEnabled, mmeAllowlist.orElse(null)))
                .withS5c(s5cWire, s5cPgwcPeerHost, s5cPgwcPeerPort)
                .withUp(upUrrMode, upUrrPollMs, upUrrBaselinePersistMs, upQerEnabled,
                        new InetSocketAddress(pfcpPeerHost, pfcpPeerPort));
        if (datastoreEnabled && dataSource != null) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource(dataSource)
                    .load()
                    .migrate();
            bootstrap.withDataSource(dataSource)
                    .withInfinispan(ispn)
                    .withSecretKek(kekHex);
            LOG.info("epc datastore enabled: Postgres Agroal(\"epc\") + Flyway wired "
                    + "(url={}, ispn={})", jdbcUrl, ispn != null && ispn.enabled());
        } else {
            LOG.info("epc datastore disabled: heap/plain path, no DB opened "
                    + "(datastore={}, ds={})", datastoreEnabled, dataSource != null);
        }
        bootstrap.attachTo(slee);
        bootstrap.bindTransport(
                new InetSocketAddress("0.0.0.0", gtpBindPort),
                new InetSocketAddress("0.0.0.0", pfcpBindPort));
        board = new LinkStatusBoard(
                bootstrap.s6a(),
                bootstrap.gtp(),
                bootstrap.pfcp(),
                new InetSocketAddress(gtpPeerHost, gtpPeerPort),
                new InetSocketAddress(pfcpPeerHost, pfcpPeerPort));
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (bootstrap != null) {
            bootstrap.stop();
        }
        if (slee != null && slee.getState() == MicroSleeContainer.State.STARTED) {
            slee.stop();
        }
    }

    public EpcBootstrap bootstrap() {
        return bootstrap;
    }

    public LinkStatusBoard board() {
        return board;
    }
}
