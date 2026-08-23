package et.epc.sgwc.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.sgwc.SgwControl;
import et.epc.sgwc.sbbs.SgwcSbb;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * micro-jainslee application bootstrap for the SGW-C role. Registers the
 * {@link SgwcSbb} type and routes GTPv2-C (S11) and PFCP (Sxa) message events
 * to it. Transport (UDP, Echo, Association/Heartbeat) stays on the vendor RAs.
 */
public final class SgwcApp {

    private static final Logger LOG = LogManager.getLogger(SgwcApp.class);

    public static final String GTP_RA = "gtpv2c-ra";
    public static final String PFCP_RA = "pfcp-ra";
    public static final String SBB_NAME = "SgwcSbb";

    public void install(MicroSleeContainer container, SgwControl sgw) {
        container.registerSbbType(SgwcSbb.class, () -> new SgwcSbb.$Concrete(sgw));
        container.mapEventToSbb(Gtpv2MessageEvent.class, SBB_NAME);
        container.mapEventToSbb(PfcpMessageEvent.class, SBB_NAME);
        LOG.info("SgwcApp installed SBB={} events=[Gtpv2MessageEvent,PfcpMessageEvent]", SBB_NAME);
    }

    public List<String> wired() {
        return List.of(SBB_NAME, GTP_RA, PFCP_RA);
    }
}