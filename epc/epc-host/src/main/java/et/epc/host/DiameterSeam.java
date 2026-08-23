package et.epc.host;

/**
 * Immutable carrier for the outbound Diameter peer seam (S6a→MME, Cx→Elisa,
 * Gx→PCEF, Rx→AF, Gy→OCS). This is the config that {@code EpcBootstrap} applies
 * to the vendor {@code ra-diameter} endpoint so it initiates CER towards a real
 * peer instead of only binding {@code 0.0.0.0:3868}.
 *
 * <p>A blank peerHost / "none" keeps the listen-only lab default; a blank
 * destination host/realm omits those AVPs (plain CER/CEA route to the peer).
 * live() is {@code isPeerReady()} (CEA accepted) — never "socket bound".</p>
 */
public record DiameterSeam(String peerHost, int peerPort, String peerRole,
                           String destinationHost, String destinationRealm,
                           String originHost, String originRealm) {

    public static final DiameterSeam DEFAULT =
            new DiameterSeam(null, 3868, "client", null, null, null, "epc.lab");

    public DiameterSeam {
        peerRole = (peerRole == null || peerRole.isBlank()) ? "client" : peerRole.trim();
        originRealm = (originRealm == null || originRealm.isBlank()) ? "epc.lab" : originRealm;
    }

    /** True when a concrete peer host is configured (CER will be initiated). */
    public boolean hasPeer() {
        return peerHost != null && !peerHost.isBlank() && !"none".equalsIgnoreCase(peerHost.trim());
    }
}