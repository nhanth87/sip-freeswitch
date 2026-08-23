package et.epc.up.linux.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.UrrIpIndex;
import et.epc.up.linux.events.UpfProgramSessionEvent;
import et.epc.up.linux.sbbs.UpfPfcpSbb;
import et.epc.up.linux.sbbs.UpfSbb;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * micro-jainslee application bootstrap for the Linux GTP user plane. One SBB
 * type owns PFCP N4 rule programming and emits Linux kernel GTP recipes; the
 * GTP-U device itself stays out of the JVM. {@link #wired()} records what was
 * bound so tests can assert install without reaching into container internals.
 */
public final class UpfApp {

    public static final String SBB_NAME = "UpfSbb";
    public static final String PFCP_SBB_NAME = "UpfPfcpSbb";

    private static final Logger LOG = LogManager.getLogger(UpfApp.class);

    private final List<String> wired = new ArrayList<>();

    public UpfApp() {}

    public void install(MicroSleeContainer container, LinuxGtpAgent agent) {
        install(container, agent, null);
    }

    /**
     * @param ipIndex optional UE-IP → URR attribution index wired into UpfPfcpSbb
     *        so kernel counter elements can be charged to the right URR.
     */
    public void install(MicroSleeContainer container, LinuxGtpAgent agent, UrrIpIndex ipIndex) {
        container.registerSbbType(UpfSbb.class, () -> new UpfSbb.$Concrete(agent));
        record("SbbType:" + SBB_NAME);

        container.mapEventToSbb(UpfProgramSessionEvent.class, SBB_NAME);
        record("EventMapping:UpfProgramSessionEvent->" + SBB_NAME);

        container.registerSbbType(UpfPfcpSbb.class, () -> new UpfPfcpSbb.$Concrete(agent, ipIndex));
        record("SbbType:" + PFCP_SBB_NAME);

        container.mapEventToSbb(PfcpMessageEvent.class, PFCP_SBB_NAME);
        record("EventMapping:PfcpMessageEvent->" + PFCP_SBB_NAME);

        LOG.info("UpfApp installed sbb={} event=UpfProgramSessionEvent; sbb={} event=PfcpMessageEvent",
                SBB_NAME, PFCP_SBB_NAME);
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