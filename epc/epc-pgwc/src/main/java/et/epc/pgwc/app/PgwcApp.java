package et.epc.pgwc.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.pgwc.PgwControl;
import et.epc.pgwc.sbbs.PgwSbb;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PGW-C application bootstrap. Registers the {@link PgwSbb} type and binds the
 * S5 (GTPv2-C) and Sxb (PFCP) inbound event classes to it. Transport/address
 * binding stays on {@code gtpv2c-ra} / {@code pfcp-ra}, wired by the caller.
 */
public final class PgwcApp {

    public static final String PGW_SBB = "PgwSbb";

    private static final Logger LOG = LogManager.getLogger(PgwcApp.class);

    private final List<String> wired = new ArrayList<>();

    public void install(MicroSleeContainer container, PgwControl pgw) {
        container.registerSbbType(PgwSbb.class, () -> new PgwSbb.$Concrete(pgw));
        container.mapEventToSbb(Gtpv2MessageEvent.class, PGW_SBB);
        container.mapEventToSbb(PfcpMessageEvent.class, PGW_SBB);
        if (!wired.contains(PGW_SBB)) {
            wired.add(PGW_SBB);
        }
        LOG.info("pgwc-app install wired={}", wired);
    }

    public List<String> wired() {
        return List.copyOf(wired);
    }
}