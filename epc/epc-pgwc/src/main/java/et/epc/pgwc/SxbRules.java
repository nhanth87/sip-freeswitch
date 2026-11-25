package et.epc.pgwc;

import com.microjainslee.ra.pfcp.PfcpFar;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpIes;
import com.microjainslee.ra.pfcp.PfcpPdr;
import com.microjainslee.ra.pfcp.PfcpQer;
import java.net.InetAddress;
import java.util.List;

/**
 * The Sxb rule set of one default bearer at the PGW-U, the IP anchor (TS 23.214 §5.2.2):
 *
 * <pre>
 *  PDR 1 (ACCESS, F-TEID S5-U PGW, UE IP src, OHR GTP-U) → FAR 1 FORW → CORE (SGi)
 *  PDR 2 (CORE,   UE IP dst)                            → FAR 2 FORW → ACCESS, OHC S5-U SGW
 * </pre>
 */
public final class SxbRules {

    public static final int UPLINK = 1, DOWNLINK = 2, QER = 1;

    private SxbRules() {}

    public static List<PfcpIe> establish(PfcpFteid pgwS5u, PfcpFteid sgwS5u, InetAddress ue) {
        return List.of(
                PfcpIes.createPdr(new PfcpPdr((short) UPLINK, 255, PfcpIe.INTERFACE_ACCESS, pgwS5u, ue,
                        UPLINK, List.of(QER), List.of(), false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new PfcpFar(UPLINK, PfcpIe.APPLY_FORW, null), PfcpIe.INTERFACE_CORE),
                PfcpIes.createPdr(new PfcpPdr((short) DOWNLINK, 254, PfcpIe.INTERFACE_CORE, null, ue,
                        DOWNLINK, List.of(QER), List.of(), false, null)),
                PfcpIes.createFar(new PfcpFar(DOWNLINK, PfcpIe.APPLY_FORW, sgwS5u), PfcpIe.INTERFACE_ACCESS),
                PfcpIes.createQer(new PfcpQer(QER)));
    }
}
