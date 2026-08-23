package et.epc.sgwc.app;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.gtpv2c.Gtpv2RaEndpoint;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import et.epc.sgwc.SgwControl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone embedded micro-jainslee bootstrap for the SGW-C role. Starts the
 * SLEE event router, registers the SgwcSbb application and binds loopback
 * S11 / Sxa transport. PFCP user-plane programming is a logging no-op here —
 * this is a bootstrap, not a live SGW-U claim.
 */
public final class SgwcMain {

    private static final Logger LOG = LogManager.getLogger(SgwcMain.class);

    private SgwcMain() {}

    public static void main(String[] args) throws Exception {
        SgwControl sgw = buildSgw();
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build();
        MicroSleeContainer container = new MicroSleeContainer(cfg);
        container.start();
        new SgwcApp().install(container, sgw);

        Gtpv2RaEndpoint gtp = new Gtpv2RaEndpoint();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        container.registerRa(gtp, gtp);
        container.registerRa(pfcp, pfcp);
        gtp.bindUdp(new InetSocketAddress("127.0.0.1", 2123));
        pfcp.bindUdp(new InetSocketAddress("127.0.0.1", 8805));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            gtp.deactivate();
            pfcp.deactivate();
            container.stop();
            LOG.info("SgwcMain stopped");
        }, "sgwc-shutdown"));

        LOG.info("SgwcMain started node=epc-sgwc-1 s11=127.0.0.1:2123 sxa=127.0.0.1:8805");
        Thread.currentThread().join();
    }

    private static SgwControl buildSgw() throws Exception {
        EpcClusterConfig cluster = EpcClusterConfig.local("epc-sgwc-1");
        HeapSessionStore sessions = new HeapSessionStore();
        // No live SGW-U: PFCP outbound commands are logged, never sent.
        RaCommandPort pfcpNoop = cmd ->
                LOG.debug("SgwcMain PfcpProgramSession ignored (no SGW-U): {}", cmd.getClass().getSimpleName());
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetSocketAddress sgwu = new InetSocketAddress("127.0.0.1", 8805);
        PgwControl pgw = new PgwControl(cluster, new Ipam(), new PcefService(),
                sessions, pfcpNoop, loopback, sgwu);
        return new SgwControl(cluster, sessions, pgw, pfcpNoop, loopback, loopback, sgwu);
    }
}