package et.epc.pcrf.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.sbbs.PcrfSbb;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * micro-jainslee application bootstrap for the PCRF process. One SBB type owns
 * the Diameter Gx/Rx gateway; the vendor {@code diameter-ra} supplies transport.
 * {@link #wired()} records what was bound so tests can assert the install
 * without reaching into the container's internal maps.
 */
public final class PcrfApp {

    public static final String SBB_NAME = "PcrfSbb";

    private static final Logger LOG = LogManager.getLogger(PcrfApp.class);

    private final List<String> wired = new ArrayList<>();

    public PcrfApp() {}

    public void install(MicroSleeContainer container, PcrfService pcrf) {
        container.registerSbbType(PcrfSbb.class, () -> new PcrfSbb.$Concrete(pcrf));
        record("SbbType:" + SBB_NAME);

        container.mapEventToSbb(DiameterRequestEvent.class, SBB_NAME);
        record("EventMapping:DiameterRequestEvent->" + SBB_NAME);

        LOG.info("PcrfApp installed sbb={} event=DiameterRequestEvent", SBB_NAME);
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