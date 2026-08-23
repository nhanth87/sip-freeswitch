package et.epc.hss.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.hss.CxService;
import et.epc.hss.HssService;
import et.epc.hss.PcrfPort;
import et.epc.hss.sbbs.HssSbb;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * micro-jainslee application bootstrap for the HSS process. One SBB type owns the
 * Diameter S6a/Cx/Gx/Rx gateway; the vendor {@code diameter-ra} supplies the
 * transport. {@link #wired()} records what was bound so tests can assert the
 * install without reaching into the container's internal maps.
 */
public final class HssApp {

    public static final String SBB_NAME = "HssSbb";

    private static final Logger LOG = LogManager.getLogger(HssApp.class);

    private final List<String> wired = new ArrayList<>();

    public HssApp() {}

    public void install(MicroSleeContainer container, HssService hss, CxService cx, PcrfPort pcrf) {
        container.registerSbbType(HssSbb.class, () -> new HssSbb.$Concrete(hss, cx, pcrf));
        record("SbbType:" + SBB_NAME);

        container.mapEventToSbb(DiameterRequestEvent.class, SBB_NAME);
        record("EventMapping:DiameterRequestEvent->" + SBB_NAME);

        LOG.info("HssApp installed sbb={} event=DiameterRequestEvent", SBB_NAME);
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