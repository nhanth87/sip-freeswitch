package et.epc.pgwc;

/**
 * Gx wire seam knobs for the PGW-C-embedded PCEF (Phase C of
 * {@code docs/agents/epc-hss-pcrf-full3gpp.md}). System property wins over env;
 * blank keeps the default.
 *
 * <ul>
 *   <li>{@code EPC_PCRF_WIRE_MODE} ({@code epc.pcrf.wire.mode}):
 *       {@code in-process} (default — static decision, no Diameter) or
 *       {@code wire} (CCR/CCA to the PCRF peer). Wire mode is still gated
 *       per-request on {@code isPeerReady()} (CEA) — never on "socket bound".</li>
 *   <li>{@code EPC_PCRF_PEER_HOST} ({@code epc.pcrf.peer.host}): PCRF Diameter
 *       peer identity, placed as Destination-Host on the CCR.</li>
 *   <li>{@code EPC_PCRF_PEER_PORT} ({@code epc.pcrf.peer.port}): PCRF peer
 *       Diameter port (default 3868). Carried for endpoint-config parity — the
 *       transport dial itself stays on {@code diameter-ra}/epc-host.</li>
 *   <li>{@code EPC_PCRF_CCR_TIMEOUT_MS} ({@code epc.pcrf.ccr.timeout-ms}):
 *       CCR→CCA wait before static fail-open fallback (default 2000).</li>
 *   <li>{@code EPC_PCRF_DEST_REALM} ({@code epc.pcrf.dest.realm}):
 *       Destination-Realm on the CCR (default {@code epc.lab}).</li>
 * </ul>
 */
public record GxWireConfig(boolean wire, String peerHost, int peerPort,
                           long ccrTimeoutMs, String destRealm) {

    public static final String MODE_IN_PROCESS = "in-process";
    public static final String MODE_WIRE = "wire";
    public static final int DEFAULT_PEER_PORT = 3868;
    public static final long DEFAULT_CCR_TIMEOUT_MS = 2_000L;
    public static final String DEFAULT_DEST_REALM = "epc.lab";

    public GxWireConfig {
        if (peerPort <= 0 || peerPort > 65_535) {
            throw new IllegalArgumentException("peer port out of range: " + peerPort);
        }
        if (ccrTimeoutMs <= 0) {
            throw new IllegalArgumentException("ccr timeout must be > 0");
        }
        peerHost = blankToNull(peerHost);
        destRealm = blankToNull(destRealm);
    }

    /** In-process default: no wire, lab-static decisions. */
    public static GxWireConfig inProcess() {
        return new GxWireConfig(false, null, DEFAULT_PEER_PORT, DEFAULT_CCR_TIMEOUT_MS,
                DEFAULT_DEST_REALM);
    }

    /** Read the knobs: system property first, then env. */
    public static GxWireConfig fromEnv() {
        String mode = lookup("EPC_PCRF_WIRE_MODE", "epc.pcrf.wire.mode", MODE_IN_PROCESS);
        boolean wire = MODE_WIRE.equalsIgnoreCase(mode);
        String host = lookup("EPC_PCRF_PEER_HOST", "epc.pcrf.peer.host", null);
        int port = parsePort(lookup("EPC_PCRF_PEER_PORT", "epc.pcrf.peer.port",
                Integer.toString(DEFAULT_PEER_PORT)));
        long timeout = parseLong(lookup("EPC_PCRF_CCR_TIMEOUT_MS", "epc.pcrf.ccr.timeout-ms",
                Long.toString(DEFAULT_CCR_TIMEOUT_MS)), DEFAULT_CCR_TIMEOUT_MS);
        String realm = lookup("EPC_PCRF_DEST_REALM", "epc.pcrf.dest.realm", DEFAULT_DEST_REALM);
        return new GxWireConfig(wire, host, port, timeout, realm);
    }

    static String lookup(String env, String sysProp, String def) {
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) {
            v = System.getenv(env);
        }
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_PEER_PORT;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}