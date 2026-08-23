package et.epc.up.linux.app;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.up.linux.LinuxGtpAgent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone embedded mode for the Linux GTP user-plane control app. Starts the
 * SLEE event router and installs the UpfSbb application. No PFCP RA is bound
 * here (R&D main only); emitted recipes are applied out-of-band by an operator
 * helper that touches the kernel GTP-U device.
 */
public final class UpfMain {

    private static final Logger LOG = LogManager.getLogger(UpfMain.class);

    private UpfMain() {}

    public static void main(String[] args) throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build();

        MicroSleeContainer container = new MicroSleeContainer(cfg);

        LinuxGtpAgent agent = new LinuxGtpAgent("gtp0");
        UpfApp app = new UpfApp();
        app.install(container, agent);

        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            container.stop();
            LOG.info("UpfMain stopped");
        }, "upf-shutdown"));

        LOG.info("epc-up-linux main started wired={} (R&D embedded mode, no PFCP transport)",
                app.wired());
        Thread.currentThread().join();
    }
}