package et.epc.pcrf.app;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.pcrf.PcrfService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone embedded mode: an in-heap PCRF (Gx/Rx decision core) wired into one
 * micro-jainslee container. No Diameter transport is bound here (R&amp;D main
 * only); {@code diameter-ra} is attached by the host process when peer routing
 * is available.
 */
public final class PcrfMain {

    private static final Logger LOG = LogManager.getLogger(PcrfMain.class);

    private PcrfMain() {}

    public static void main(String[] args) throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build();

        MicroSleeContainer container = new MicroSleeContainer(cfg);

        PcrfApp app = new PcrfApp();
        app.install(container, PcrfService.withHeapPolicy());

        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "pcrf-shutdown"));

        LOG.info("epc-pcrf main started wired={} (R&D embedded mode, no Diameter transport)",
                app.wired());
        Thread.currentThread().join();
    }
}