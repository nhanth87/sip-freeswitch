package et.epc.hss.app;

import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.RaEndpointPort;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import com.microjainslee.ra.diameter.DiameterResourceAdaptor;
import com.microjainslee.ra.httpserver.HttpServerRaEndpoint;
import com.microjainslee.ra.httpserver.HttpServerResourceAdaptor;
import com.microjainslee.ra.jss7.Ss7RaConfig;
import com.microjainslee.ra.jss7.Ss7RaEndpoint;
import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import et.epc.api.PlmnId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The three transport links of the standalone HSS process — configuration and binder.
 *
 * <h2>Links</h2>
 * <ul>
 *   <li><b>Diameter</b> (S6a / Cx) on {@code diameter-ra}, TCP or SCTP;</li>
 *   <li><b>SS7</b> (MAP SendAuthenticationInfo, HLR SSN 6) on {@code ra-jss7}, M3UA over
 *       <b>SCTP only</b> — RFC 4666 makes SCTP the M3UA transport; any other channel is refused at
 *       config time;</li>
 *   <li><b>SBI</b> (Nudm-UEAU) on {@code ra-http-server}: HTTP/2 cleartext with prior knowledge
 *       (TS 29.500 §5.2.2), HTTP/1.1 also served.</li>
 * </ul>
 * Every link is optional and off by default. Keys are read from {@code -D} system properties first,
 * then from the environment ({@code hss.sbi.port} → {@code HSS_SBI_PORT}).
 *
 * <h2>Status truth</h2>
 * A link is reported up only after its RA activated without error <em>and</em> the RA itself says
 * it is active; an enabled link that fails is never reported up — {@link Bound#verify()} throws.
 * Bound/listening is not peer up: Diameter reports {@code peerReady} (CER/CEA done) and SS7
 * reports {@code m3uaRouteReady} (SCTP association + AS ACTIVE) separately, and both are
 * {@code false} at boot until a peer has answered.
 *
 * <h2>Timers</h2>
 * Diameter watchdog ({@code hss.diameter.watchdog-ms}, Tw silence limit), MAP invoke and dialog
 * idle timeouts ({@code hss.ss7.invoke-timeout-ms}, {@code hss.ss7.dialog-idle-timeout-ms}) and the
 * SBI response deadline ({@code hss.sbi.response-timeout-ms}: an SBB that never answers yields a
 * 504 from the RA, never a hung stream) all live in the RAs.
 *
 * <h2>KEK</h2>
 * {@link #resolveKek(String, boolean)} refuses the public {@link HssBootstrap#DEFAULT_KEK_HEX}
 * (and a blank KEK, which falls back to it) unless {@code hss.lab=true}. The KEK value is never
 * logged or printed by {@link Config#toString()}.
 */
public final class HssLinks {

    // ---------------------------------------------------------------- keys

    public static final String LAB = "hss.lab";
    public static final String KEK = "hss.secret.kek-hex";

    public static final String DIAMETER_ENABLED = "hss.diameter.enabled";
    public static final String DIAMETER_HOST = "hss.diameter.host";
    public static final String DIAMETER_PORT = "hss.diameter.port";
    public static final String DIAMETER_ORIGIN_HOST = "hss.diameter.origin-host";
    public static final String DIAMETER_ORIGIN_REALM = "hss.diameter.origin-realm";
    public static final String DIAMETER_TRANSPORT = "hss.diameter.transport";
    /** {@code DiameterRaConfig.parsePeers} spec, e.g. {@code mme:3868:client:mme.epc.lab:epc.lab:s6a}. */
    public static final String DIAMETER_PEERS = "hss.diameter.peers";
    public static final String DIAMETER_WATCHDOG_MS = "hss.diameter.watchdog-ms";

    public static final String SS7_ENABLED = "hss.ss7.enabled";
    public static final String SS7_CHANNEL = "hss.ss7.channel";
    public static final String SS7_HOST_IP = "hss.ss7.host-ip";
    public static final String SS7_HOST_PORT = "hss.ss7.host-port";
    public static final String SS7_PEER_IP = "hss.ss7.peer-ip";
    public static final String SS7_PEER_PORT = "hss.ss7.peer-port";
    public static final String SS7_OPC = "hss.ss7.opc";
    public static final String SS7_DPC = "hss.ss7.dpc";
    public static final String SS7_LOCAL_SSN = "hss.ss7.local-ssn";
    public static final String SS7_REMOTE_SSN = "hss.ss7.remote-ssn";
    public static final String SS7_ROUTING_CONTEXT = "hss.ss7.routing-context";
    public static final String SS7_NETWORK_APPEARANCE = "hss.ss7.network-appearance";
    public static final String SS7_INVOKE_TIMEOUT_MS = "hss.ss7.invoke-timeout-ms";
    public static final String SS7_DIALOG_IDLE_TIMEOUT_MS = "hss.ss7.dialog-idle-timeout-ms";

    public static final String SBI_ENABLED = "hss.sbi.enabled";
    public static final String SBI_HOST = "hss.sbi.host";
    public static final String SBI_PORT = "hss.sbi.port";
    public static final String SBI_RESPONSE_TIMEOUT_MS = "hss.sbi.response-timeout-ms";
    public static final String SBI_ALLOWED_SERVING_PLMNS = "hss.sbi.allowed-serving-plmns";

    // ---------------------------------------------------------------- defaults

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_DIAMETER_PORT = 3868;
    public static final String DEFAULT_ORIGIN_HOST = "hss.epc.lab";
    public static final String DEFAULT_ORIGIN_REALM = "epc.lab";
    public static final long DEFAULT_DIAMETER_WATCHDOG_MS = 30_000L;
    public static final int DEFAULT_SS7_HOST_PORT = 2905;
    public static final int DEFAULT_SS7_PEER_PORT = 2906;
    /** TS 23.003 §8.1 / Q.713 Annex B: SSN 6 = HLR, SSN 7 = VLR. */
    public static final int SSN_HLR = 6;
    public static final int SSN_VLR = 7;
    public static final long DEFAULT_SS7_INVOKE_TIMEOUT_MS = 120_000L;
    public static final long DEFAULT_SS7_DIALOG_IDLE_TIMEOUT_MS = 300_000L;
    public static final int DEFAULT_SBI_PORT = 7777;
    public static final long DEFAULT_SBI_RESPONSE_TIMEOUT_MS = 5_000L;

    private static final Logger LOG = LogManager.getLogger(HssLinks.class);

    private HssLinks() {}

    // ---------------------------------------------------------------- config source

    /** Config source: one key → raw value, {@code null} when absent. */
    @FunctionalInterface
    public interface Lookup {
        String get(String key);

        /** {@code -D} system property first, then the environment ({@link #envName}). */
        static Lookup system() {
            return key -> {
                String v = System.getProperty(key);
                return v != null ? v : System.getenv(envName(key));
            };
        }

        static Lookup of(Map<String, String> values) {
            Map<String, String> copy = Map.copyOf(values);
            return copy::get;
        }
    }

    /** {@code hss.sbi.allowed-serving-plmns} → {@code HSS_SBI_ALLOWED_SERVING_PLMNS}. */
    public static String envName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    // ---------------------------------------------------------------- config records

    public enum DiameterTransport { TCP, SCTP }

    /** Diameter link (S6a / Cx); {@code peers} empty = the RA's single server-role peer. */
    public record Diameter(boolean enabled, String host, int port, String originHost, String originRealm,
                           DiameterTransport transport, String peers, long watchdogMs) {

        public Diameter {
            Objects.requireNonNull(transport, "transport");
            peers = peers == null ? "" : peers.trim();
            // fail at config time, not at RA activation
            DiameterRaConfig.parsePeers(peers);
        }

        public DiameterRaConfig toRaConfig() {
            DiameterRaConfig cfg = new DiameterRaConfig();
            cfg.setHost(host);
            cfg.setPort(port);
            cfg.setOriginHost(originHost);
            cfg.setRealm(originRealm);
            cfg.setProductName("epc-hss");
            cfg.setTcpEnabled(transport == DiameterTransport.TCP);
            cfg.setSctpEnabled(transport == DiameterTransport.SCTP);
            cfg.setWatchdogTimeoutMs(watchdogMs);
            if (!peers.isEmpty()) {
                cfg.setPeers(DiameterRaConfig.parsePeers(peers));
            }
            return cfg;
        }
    }

    /** SS7 link: M3UA over SCTP towards the VLR/SGSN side, MAP only (no CAP). */
    public record Ss7(boolean enabled, String hostIp, int hostPort, String peerIp, int peerPort,
                      int opc, int dpc, int localSsn, int remoteSsn, long routingContext,
                      long networkAppearance, long invokeTimeoutMs, long dialogIdleTimeoutMs) {

        public Ss7RaConfig toRaConfig() {
            return new Ss7RaConfig()
                    .stackName("epc-hss-ss7")
                    .associationName("epc-hss-assoc")
                    .ipChannelType("SCTP")
                    .hostIp(hostIp)
                    .hostPort(hostPort)
                    .peerIp(peerIp)
                    .peerPort(peerPort)
                    .originatingPointCode(opc)
                    .destinationPointCode(dpc)
                    .localSsn(localSsn)
                    .remoteSsn(remoteSsn)
                    .routingContext(routingContext)
                    .networkAppearance(networkAppearance)
                    .invokeTimeoutMs(invokeTimeoutMs)
                    .dialogIdleTimeoutMs(dialogIdleTimeoutMs)
                    .mapEnabled(true)
                    .capEnabled(false);
        }
    }

    /** SBI link (Nudm-UEAU) and the serving-network allowlist fed to {@code UeauService}. */
    public record Sbi(boolean enabled, String host, int port, long responseTimeoutMs,
                      List<PlmnId> allowedServingPlmns) {

        public Sbi {
            allowedServingPlmns = List.copyOf(allowedServingPlmns);
        }
    }

    /** All links + lab flag. The KEK is held but never printed. */
    public record Config(boolean lab, String kekHex, Diameter diameter, Ss7 ss7, Sbi sbi) {

        /** The KEK to use; refuses the public default unless {@link #lab()}. */
        public String kek() {
            return resolveKek(kekHex, lab);
        }

        /** The {@link HssApp} faces to install: exactly the enabled links. */
        public Set<HssApp.Face> faces() {
            Set<HssApp.Face> f = EnumSet.noneOf(HssApp.Face.class);
            if (diameter.enabled()) {
                f.add(HssApp.Face.DIAMETER);
            }
            if (ss7.enabled()) {
                f.add(HssApp.Face.SS7);
            }
            if (sbi.enabled()) {
                f.add(HssApp.Face.SBI);
            }
            return f;
        }

        @Override
        public String toString() {
            return "Config[lab=" + lab + ", kekHex=<redacted>, diameter=" + diameter + ", ss7=" + ss7
                    + ", sbi=" + sbi + "]";
        }
    }

    // ---------------------------------------------------------------- parsing

    public static Config load(Lookup lookup) {
        Objects.requireNonNull(lookup, "lookup");
        boolean lab = bool(lookup, LAB, false);
        String kek = lookup.get(KEK);

        Diameter diameter = new Diameter(
                bool(lookup, DIAMETER_ENABLED, false),
                str(lookup, DIAMETER_HOST, DEFAULT_HOST),
                port(lookup, DIAMETER_PORT, DEFAULT_DIAMETER_PORT),
                str(lookup, DIAMETER_ORIGIN_HOST, DEFAULT_ORIGIN_HOST),
                str(lookup, DIAMETER_ORIGIN_REALM, DEFAULT_ORIGIN_REALM),
                diameterTransport(lookup.get(DIAMETER_TRANSPORT)),
                str(lookup, DIAMETER_PEERS, ""),
                nonNegativeLong(lookup, DIAMETER_WATCHDOG_MS, DEFAULT_DIAMETER_WATCHDOG_MS));

        requireSctp(lookup.get(SS7_CHANNEL));
        Ss7 ss7 = new Ss7(
                bool(lookup, SS7_ENABLED, false),
                str(lookup, SS7_HOST_IP, DEFAULT_HOST),
                port(lookup, SS7_HOST_PORT, DEFAULT_SS7_HOST_PORT),
                str(lookup, SS7_PEER_IP, DEFAULT_HOST),
                port(lookup, SS7_PEER_PORT, DEFAULT_SS7_PEER_PORT),
                range(lookup, SS7_OPC, 1, 0, 0xFFFFFF),
                range(lookup, SS7_DPC, 2, 0, 0xFFFFFF),
                range(lookup, SS7_LOCAL_SSN, SSN_HLR, 1, 255),
                range(lookup, SS7_REMOTE_SSN, SSN_VLR, 1, 255),
                nonNegativeLong(lookup, SS7_ROUTING_CONTEXT, 100L),
                nonNegativeLong(lookup, SS7_NETWORK_APPEARANCE, 102L),
                nonNegativeLong(lookup, SS7_INVOKE_TIMEOUT_MS, DEFAULT_SS7_INVOKE_TIMEOUT_MS),
                nonNegativeLong(lookup, SS7_DIALOG_IDLE_TIMEOUT_MS, DEFAULT_SS7_DIALOG_IDLE_TIMEOUT_MS));

        Sbi sbi = new Sbi(
                bool(lookup, SBI_ENABLED, false),
                str(lookup, SBI_HOST, DEFAULT_HOST),
                port(lookup, SBI_PORT, DEFAULT_SBI_PORT),
                nonNegativeLong(lookup, SBI_RESPONSE_TIMEOUT_MS, DEFAULT_SBI_RESPONSE_TIMEOUT_MS),
                parsePlmns(lookup.get(SBI_ALLOWED_SERVING_PLMNS)));

        return new Config(lab, kek, diameter, ss7, sbi);
    }

    /**
     * SS7 transport is SCTP only (RFC 4666: M3UA runs over SCTP; repo rule). Absent = SCTP; any
     * other value — {@code TCP}, a blank value, a typo — is refused so a misconfiguration can never
     * silently ride TCP.
     */
    static void requireSctp(String channel) {
        if (channel == null) {
            return;
        }
        if (!"sctp".equalsIgnoreCase(channel.trim())) {
            throw new IllegalArgumentException(SS7_CHANNEL + "='" + channel + "' refused: SS7 (M3UA) transport "
                    + "is SCTP only (RFC 4666). Remove the key or set it to SCTP; for a lab without the "
                    + "native F-Stack library use kernel SCTP (sctp_backend=NETTY_KERNEL), never TCP.");
        }
    }

    static DiameterTransport diameterTransport(String raw) {
        if (raw == null) {
            return DiameterTransport.TCP;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "tcp" -> DiameterTransport.TCP;
            case "sctp" -> DiameterTransport.SCTP;
            default -> throw new IllegalArgumentException(DIAMETER_TRANSPORT + "='" + raw
                    + "' refused: expected tcp or sctp");
        };
    }

    /**
     * {@code "00101,45204"} → PLMNs. Each entry is MCC (3 digits) + MNC (2 or 3 digits), optionally
     * written {@code 001-01}. Blank / absent = none (home PLMN only).
     */
    public static List<PlmnId> parsePlmns(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<PlmnId> out = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String e = raw.trim();
            if (e.isEmpty()) {
                continue;
            }
            String mcc;
            String mnc;
            int dash = e.indexOf('-');
            if (dash >= 0) {
                mcc = e.substring(0, dash);
                mnc = e.substring(dash + 1);
            } else if (e.length() == 5 || e.length() == 6) {
                mcc = e.substring(0, 3);
                mnc = e.substring(3);
            } else {
                throw new IllegalArgumentException(SBI_ALLOWED_SERVING_PLMNS + " entry '" + e
                        + "' is not MCC+MNC (5 or 6 digits, e.g. 00101 or 001-01)");
            }
            PlmnId plmn;
            try {
                plmn = new PlmnId(mcc, mnc);
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(SBI_ALLOWED_SERVING_PLMNS + " entry '" + e + "': "
                        + ex.getMessage(), ex);
            }
            if (!out.contains(plmn)) {
                out.add(plmn);
            }
        }
        return List.copyOf(out);
    }

    /**
     * The KEK that wraps Ki/OPc at rest. A blank value falls back to the public
     * {@link HssBootstrap#DEFAULT_KEK_HEX}; that value is refused unless {@code lab} is set, then
     * it is accepted with a WARN. The value is never logged.
     */
    public static String resolveKek(String configured, boolean lab) {
        String kek = configured == null ? "" : configured.trim();
        boolean isDefault = kek.isEmpty() || kek.equalsIgnoreCase(HssBootstrap.DEFAULT_KEK_HEX);
        if (!isDefault) {
            return kek;
        }
        if (!lab) {
            throw new IllegalStateException("refusing to start: " + KEK + " is "
                    + (kek.isEmpty() ? "unset" : "the public default KEK")
                    + " — Ki/OPc would be wrapped with a key published in the source tree. Set a real KEK "
                    + "(-D" + KEK + " or " + envName(KEK) + "), or -D" + LAB + "=true for a lab.");
        }
        LOG.warn("epc-hss LAB MODE ({}=true): using the public default KEK — never on a host with real "
                + "subscriber secrets", LAB);
        return HssBootstrap.DEFAULT_KEK_HEX;
    }

    private static String str(Lookup l, String key, String def) {
        String v = l.get(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static boolean bool(Lookup l, String key, boolean def) {
        String v = l.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + "='" + v + "' is not true|false");
        };
    }

    private static long parseLong(String key, String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + "='" + v + "' is not a number", e);
        }
    }

    private static int range(Lookup l, String key, int def, int min, int max) {
        String v = l.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        long n = parseLong(key, v);
        if (n < min || n > max) {
            throw new IllegalArgumentException(key + "=" + n + " out of range [" + min + ".." + max + "]");
        }
        return (int) n;
    }

    private static int port(Lookup l, String key, int def) {
        return range(l, key, def, 0, 65_535);
    }

    private static long nonNegativeLong(Lookup l, String key, long def) {
        String v = l.get(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        long n = parseLong(key, v);
        if (n < 0) {
            throw new IllegalArgumentException(key + "=" + n + " must be >= 0");
        }
        return n;
    }

    // ---------------------------------------------------------------- binder

    /**
     * Register the RA of every enabled link on {@code container} (before {@code start()}; the
     * container activates them on start). Call {@link Bound#verify()} after {@code start()}.
     */
    public static Bound bind(MicroSleeContainer container, Config cfg) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(cfg, "config");
        Bound b = new Bound(cfg);
        if (cfg.diameter().enabled()) {
            DiameterRaEndpoint ep = new DiameterRaEndpoint(new DiameterResourceAdaptor());
            ep.setConfig(cfg.diameter().toRaConfig());
            b.diameter = ep;
            b.diameterProbe = new ActivationProbe(ep);
            container.registerRa(b.diameterProbe, ep);
        }
        if (cfg.ss7().enabled()) {
            Ss7ResourceAdaptor ra = new Ss7ResourceAdaptor();
            ra.setConfig(cfg.ss7().toRaConfig());
            Ss7RaEndpoint ep = new Ss7RaEndpoint(ra);
            b.ss7Ra = ra;
            b.ss7Probe = new ActivationProbe(ep);
            container.registerRa(b.ss7Probe, ep);
        }
        if (cfg.sbi().enabled()) {
            HttpServerResourceAdaptor ra = new HttpServerResourceAdaptor();
            ra.setHost(cfg.sbi().host());
            ra.setPort(cfg.sbi().port());
            ra.setResponseTimeoutMs(cfg.sbi().responseTimeoutMs());
            HttpServerRaEndpoint ep = new HttpServerRaEndpoint(ra);
            b.http = ep;
            b.httpProbe = new ActivationProbe(ep);
            container.registerRa(b.httpProbe, ep);
        }
        return b;
    }

    /** Handle on the bound links; every status string is RA truth, never configuration. */
    public static final class Bound {
        private final Config cfg;
        private DiameterRaEndpoint diameter;
        private ActivationProbe diameterProbe;
        private Ss7ResourceAdaptor ss7Ra;
        private ActivationProbe ss7Probe;
        private HttpServerRaEndpoint http;
        private ActivationProbe httpProbe;

        private Bound(Config cfg) {
            this.cfg = cfg;
        }

        public boolean diameterBound() {
            return diameter != null && diameterProbe.activated() && diameter.delegate().isActive();
        }

        public boolean ss7Active() {
            return ss7Ra != null && ss7Probe.activated() && ss7Ra.isActive();
        }

        public boolean sbiListening() {
            return http != null && httpProbe.activated() && http.delegate().isActive() && http.port() > 0;
        }

        /** Actual SBI listen port (after an ephemeral bind), or -1 when not listening. */
        public int sbiPort() {
            return sbiListening() ? http.port() : -1;
        }

        public String diameterStatus() {
            Diameter d = cfg.diameter();
            if (!d.enabled()) {
                return "off";
            }
            if (!diameterBound()) {
                return "FAILED(" + reason(diameterProbe) + ")";
            }
            return "bound(" + d.transport().name().toLowerCase(Locale.ROOT) + " " + d.host() + ":" + d.port()
                    + ", peerReady=" + diameter.isPeerReady() + ")";
        }

        public String ss7Status() {
            Ss7 s = cfg.ss7();
            if (!s.enabled()) {
                return "off";
            }
            if (!ss7Active()) {
                return "FAILED(" + reason(ss7Probe) + ")";
            }
            return "active(sctp " + s.hostIp() + ":" + s.hostPort() + "->" + s.peerIp() + ":" + s.peerPort()
                    + " opc=" + s.opc() + " dpc=" + s.dpc() + " ssn=" + s.localSsn() + "/" + s.remoteSsn()
                    + ", m3uaRouteReady=" + ss7Ra.isM3uaRouteReady() + ")";
        }

        public String sbiStatus() {
            Sbi s = cfg.sbi();
            if (!s.enabled()) {
                return "off";
            }
            if (!sbiListening()) {
                return "FAILED(" + reason(httpProbe) + ")";
            }
            return "listening h2c " + s.host() + ":" + http.port();
        }

        /** {@code diameter=… ss7=… sbi=…}. */
        public String statusLine() {
            return "diameter=" + diameterStatus() + " ss7=" + ss7Status() + " sbi=" + sbiStatus();
        }

        /**
         * After {@code container.start()}: every enabled link must be up locally (bound / active /
         * listening). Throws listing each failed link — an enabled link that failed is never
         * reported up.
         */
        public void verify() {
            List<String> failed = new ArrayList<>();
            if (cfg.diameter().enabled() && !diameterBound()) {
                failed.add("diameter " + diameterStatus());
            }
            if (cfg.ss7().enabled() && !ss7Active()) {
                failed.add("ss7 " + ss7Status());
            }
            if (cfg.sbi().enabled() && !sbiListening()) {
                failed.add("sbi " + sbiStatus());
            }
            if (!failed.isEmpty()) {
                throw new IllegalStateException("epc-hss link(s) failed to come up: " + String.join("; ", failed));
            }
        }

        private static String reason(ActivationProbe p) {
            if (p == null) {
                return "not registered";
            }
            RuntimeException f = p.failure();
            if (f != null) {
                Throwable root = f;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                return "activation failed: " + root.getClass().getSimpleName()
                        + (root.getMessage() == null ? "" : " " + root.getMessage());
            }
            return p.activated() ? "RA not active after activation" : "RA not activated";
        }
    }

    /**
     * Wraps an RA endpoint to record whether {@code activate} returned or threw: the container logs
     * and swallows an activation failure, and some RAs flip their own "active" flag before the
     * transport has started, so the RA flag alone is not proof.
     */
    static final class ActivationProbe implements RaEndpointPort {
        private final RaEndpointPort delegate;
        private volatile boolean activated;
        private volatile RuntimeException failure;

        ActivationProbe(RaEndpointPort delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void activate(RaBootstrapPort bootstrap) {
            try {
                delegate.activate(bootstrap);
                failure = null;
                activated = true;
            } catch (RuntimeException e) {
                activated = false;
                failure = e;
                throw e;
            }
        }

        @Override
        public void deactivate() {
            activated = false;
            delegate.deactivate();
        }

        @Override
        public String getRaName() {
            return delegate.getRaName();
        }

        boolean activated() {
            return activated;
        }

        RuntimeException failure() {
            return failure;
        }
    }
}
