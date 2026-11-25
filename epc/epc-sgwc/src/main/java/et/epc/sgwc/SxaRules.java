package et.epc.sgwc;

import com.microjainslee.ra.pfcp.PfcpFar;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpIes;
import com.microjainslee.ra.pfcp.PfcpPdr;
import com.microjainslee.ra.pfcp.PfcpQer;
import et.epc.api.Fteid;
import java.net.InetAddress;
import java.util.List;

/**
 * The Sxa rule set of one default bearer at the SGW-U (TS 29.244 §5.2, TS 23.214 §5.2.2):
 *
 * <pre>
 *  PDR 1 (ACCESS, F-TEID S1-U SGW, OHR GTP-U) → FAR 1 FORW  → CORE,   OHC S5-U PGW
 *  PDR 2 (CORE,   F-TEID S5-U SGW, OHR GTP-U) → FAR 2 BUFF|NOCP until Modify Bearer,
 *                                               then FORW → ACCESS, OHC S1-U eNB
 * </pre>
 *
 * Modify Bearer and Release Access Bearers only ever touch FAR 2 (Update FAR), so the
 * Session Modification carries no state that has to be read back first.
 */
public final class SxaRules {

    public static final int UPLINK = 1, DOWNLINK = 2, QER = 1;

    private SxaRules() {}

    /** Session Establishment IEs (Create PDR / FAR / QER); Node ID and CP F-SEID come from pfcp-ra. */
    public static List<PfcpIe> establish(Fteid sgwS1u, Fteid sgwS5u, Fteid pgwS5u, Fteid enbS1u, InetAddress ue) {
        PfcpFar dl = enbS1u == null
                ? new PfcpFar(DOWNLINK, PfcpIe.APPLY_BUFF | PfcpIe.APPLY_NOCP, null)
                : new PfcpFar(DOWNLINK, PfcpIe.APPLY_FORW, pf(enbS1u));
        return List.of(
                PfcpIes.createPdr(new PfcpPdr((short) UPLINK, 255, PfcpIe.INTERFACE_ACCESS, pf(sgwS1u), ue,
                        UPLINK, List.of(QER), List.of(), false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(new PfcpFar(UPLINK, PfcpIe.APPLY_FORW, pf(pgwS5u)), PfcpIe.INTERFACE_CORE),
                PfcpIes.createPdr(new PfcpPdr((short) DOWNLINK, 254, PfcpIe.INTERFACE_CORE, pf(sgwS5u), ue,
                        DOWNLINK, List.of(QER), List.of(), false, PfcpIes.OHR_GTPU_UDP_IPV4)),
                PfcpIes.createFar(dl, PfcpIe.INTERFACE_ACCESS),
                PfcpIes.createQer(new PfcpQer(QER)));
    }

    /** Modify Bearer: Update FAR 2 → FORW, Outer Header Creation toward the eNB (§7.5.4.3). */
    public static List<PfcpIe> downlinkTo(Fteid enbS1u) {
        return List.of(PfcpIes.updateFar(DOWNLINK, PfcpIe.APPLY_FORW, pf(enbS1u), PfcpIe.INTERFACE_ACCESS));
    }

    /** Release Access Bearers: Update FAR 2 → BUFF | NOCP (the UP buffers and reports DL data). */
    public static List<PfcpIe> downlinkBuffered() {
        return List.of(PfcpIes.updateFar(DOWNLINK, PfcpIe.APPLY_BUFF | PfcpIe.APPLY_NOCP, null,
                PfcpIe.INTERFACE_ACCESS));
    }

    static PfcpFteid pf(Fteid f) {
        return new PfcpFteid(f.teid(), f.address(), f.interfaceType());
    }
}
