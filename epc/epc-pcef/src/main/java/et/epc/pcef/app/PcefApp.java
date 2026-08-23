package et.epc.pcef.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.pcef.PcefService;
import et.epc.pcef.sbbs.PcefSbb;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * micro-jainslee application bootstrap for a standalone PCEF process. One SBB
 * type ({@link PcefSbb}) owns the policy-and-charging decision flow
 * ({@link PcefService}); the vendor {@code diameter-ra} supplies the Gx
 * transport. {@link #wired()} records what was bound so tests can assert the
 * install without reaching into the container's internal maps.
 *
 * <p><b>Standalone note.</b> PCEF normally runs inside PGW-C (epc-pgwc). Standalone PCEF
 * runs the <em>decision core</em>; Gx transport is wired via the PcrfPort-style delegation
 * seam in the host when Diameter peer routing is available (R&amp;D main here binds no socket).</p>
 */
public final class PcefApp {

    public static final String SBB_NAME = "PcefSbb";

    private static final Logger LOG = LogManager.getLogger(PcefApp.class);

    private final List<String> wired = new ArrayList<>();

    public PcefApp() {}

    public void install(MicroSleeContainer container, PcefService pcef) {
        container.registerSbbType(PcefSbb.class, () -> new PcefSbb.$Concrete(pcef));
        record("SbbType:" + SBB_NAME);

        container.mapEventToSbb(DiameterRequestEvent.class, SBB_NAME);
        record("EventMapping:DiameterRequestEvent->" + SBB_NAME);

        LOG.info("PcefApp installed sbb={} event=DiameterRequestEvent", SBB_NAME);
    }

    public List<String> wired() {
        return List.copyOf(wired);
    }

    private void record(String entry) {
        if (!wired.contains(entry)) {
            wired.add(entry);
        }
    }
}