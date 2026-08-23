package et.epc.pcef;

import et.epc.api.Imsi;

/**
 * Gx client contract used by {@link PcefService}. The PCRF is a Diameter peer;
 * {@code PcefService} never builds CCR itself — it delegates to this client
 * only when {@code pcrfLive} is true, keeping the static fallback otherwise.
 *
 * <p>Concrete Diameter transport lives in {@code epc-host} (ra-diameter only);
 * this interface keeps the pcef→pcrf dependency direction clean.</p>
 */
public interface PcrfClient {

    /** Establish/refresh an IP-CAN (Gx) session and return the installed decision. */
    PolicyDecision beginGxSession(Imsi imsi, String apn, String ueIpv4,
                                  long chargingId, String requestType);
}