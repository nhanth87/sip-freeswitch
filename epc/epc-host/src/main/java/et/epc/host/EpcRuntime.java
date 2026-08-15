package et.epc.host;

import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.cluster.ClusterMode;
import et.epc.api.cluster.EpcClusterConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.net.InetSocketAddress;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class EpcRuntime {

    @ConfigProperty(name = "epc.role.hss", defaultValue = "true")
    boolean roleHss;

    @ConfigProperty(name = "epc.role.sgwc", defaultValue = "true")
    boolean roleSgwc;

    @ConfigProperty(name = "epc.role.pgwc", defaultValue = "true")
    boolean rolePgwc;

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

    private EpcBootstrap bootstrap;
    private LinkStatusBoard board;
    private MicroSleeContainer slee;

    void onStart(@Observes StartupEvent ev) {
        EpcRoles roles = EpcRoles.fromEnv(roleHss, roleSgwc, rolePgwc);
        EpcClusterConfig cfg = new EpcClusterConfig(
                nodeId, ClusterMode.valueOf(mode), clusterName, nodeIndex);
        slee = new MicroSleeContainer();
        bootstrap = new EpcBootstrap(roles, cfg);
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
