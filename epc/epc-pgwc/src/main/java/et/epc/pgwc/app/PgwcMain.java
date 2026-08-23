package et.epc.pgwc.app;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone embedded PGW-C process. The S5/Sxb RAs would bind UDP via
 * {@code gtpv2c-ra} / {@code pfcp-ra} in the host image; here both command
 * ports are no-ops so the container starts without sockets in a lab shell.
 */
public final class PgwcMain {

    private static final Logger LOG = LogManager.getLogger(PgwcMain.class);

    private PgwcMain() {
    }

    public static void main(String[] args) throws Exception {
        MicroSleeConfiguration config = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build();
        MicroSleeContainer container = new MicroSleeContainer(config);

        RaCommandPort noop = command -> {};
        EpcClusterConfig cluster = EpcClusterConfig.local("epc-pgw-1");
        Ipam ipam = new Ipam();
        PcefService pcef = new PcefService();
        HeapSessionStore sessions = new HeapSessionStore();
        PgwControl pgw = new PgwControl(cluster, ipam, pcef, sessions, noop,
                InetAddress.getByName("127.0.0.1"),
                new InetSocketAddress("127.0.0.1", 8805));

        container.registerRaCommandPort("gtpv2c-ra", noop);
        container.registerRaCommandPort("pfcp-ra", noop);
        PgwcApp app = new PgwcApp();
        app.install(container, pgw);
        container.start();

        Runtime.getRuntime().addShutdownHook(new Thread(container::stop));
        LOG.info("pgwc-main node={} wired={}", cluster.nodeId(), app.wired());
        Thread.currentThread().join();
    }
}