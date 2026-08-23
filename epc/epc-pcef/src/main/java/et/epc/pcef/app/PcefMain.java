package et.epc.pcef.app;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.pcef.PcefService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone embedded mode for PCEF: an in-heap decision core
 * ({@link PcefService}) wired into one micro-jainslee container. No Diameter
 * transport is bound here (R&amp;D main only); {@code diameter-ra} Gx is attached
 * by the host via the PcrfPort-style delegation seam when peer routing is available.
 * PCEF standalone runs the decision core; Gx transport is wired via PcrfPort in host.
 */
public final class PcefMain {

    private static final Logger LOG = LogManager.getLogger(PcefMain.class);

    private PcefMain() {}

    public static void main(String[] args) throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build();

        MicroSleeContainer container = new MicroSleeContainer(cfg);

        PcefApp app = new PcefApp();
        app.install(container, new PcefService());

        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "pcef-shutdown"));

        LOG.info("epc-pcef main started wired={} (R&D embedded mode, no Diameter transport; "
                + "Gx wired via PcrfPort in host)", app.wired());
        Thread.currentThread().join();
    }
}