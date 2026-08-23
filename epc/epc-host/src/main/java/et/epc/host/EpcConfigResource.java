package et.epc.host;

import et.epc.pcrf.PcrfService;
import et.epc.up.linux.UrrPoller;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Configuration endpoint for the EPC control plane: a read-only effective-config
 * view for boot-time parameters plus a narrow runtime-mutable PUT seam.
 *
 * <p>Runtime-mutable keys (PUT {@code /epc/config/{key}}, Phase D):</p>
 * <ul>
 *   <li>{@value #KEY_USAGE_MONITOR_THRESHOLD} — long ≥ 0 bytes, applied live via
 *   {@link PcrfService#withUsageMonitorThreshold(long)} when the PCRF service is wired.</li>
 *   <li>{@value #KEY_UP_URR_MODE} — {@code poll|off}, applied live by
 *   starting/stopping the boot-wired {@link UrrPoller}; when no poller was wired
 *   at boot the value is stored as a volatile override served on GET.</li>
 *   <li>{@value #KEY_UP_URR_POLL_MS} — long ≥ 100 ms. The {@code UrrPoller} cadence
 *   is final at boot, so this is stored as a volatile override, served on GET and
 *   consumed by the next boot (documented in the PUT response {@code note}).</li>
 * </ul>
 *
 * <p>Every other knob stays boot-time only ({@code writable=false} on GET): the
 * transport/ISPN topology (cluster, node-id, roles) and the GTP/PFCP peer+bind
 * ports, plus the datastore flag (DATASTORE wiring happens at boot). Kept on the
 * EPC host (port 8099) — never on the Elisa admin. Runtime overrides are volatile:
 * a restart falls back to the boot-time properties (config stays the SoT at boot).</p>
 */
@Path("/epc/config")
public class EpcConfigResource {

    /** Runtime-mutable: PCRF Usage-Monitoring volume threshold (bytes, long ≥ 0). */
    public static final String KEY_USAGE_MONITOR_THRESHOLD = "epc.pcrf.usage-monitor-threshold";
    /** Runtime-mutable: UP URR mode {@code poll|off}. */
    public static final String KEY_UP_URR_MODE = "epc.up.urr.mode";
    /** Runtime-requested: UP URR poll cadence ms (≥ 100); UrrPoller cadence is boot-time final. */
    public static final String KEY_UP_URR_POLL_MS = "epc.up.urr.poll-ms";

    /** The only keys PUT accepts; everything else is boot-time only (404 on PUT). */
    public static final List<String> MUTABLE_KEYS =
            List.of(KEY_USAGE_MONITOR_THRESHOLD, KEY_UP_URR_MODE, KEY_UP_URR_POLL_MS);

    /** Mirrors the {@code PcrfService} boot default (1 GB pilot volume). */
    static final long USAGE_MONITOR_THRESHOLD_BOOT_DEFAULT = 1_000_000_000L;

    /** Process-wide overrides; PUT writes, GET reads. Volatile (lost on restart). */
    static final RuntimeOverrides OVERRIDES = new RuntimeOverrides();

    /**
     * Volatile runtime overrides for knobs that could not be applied to a live
     * service (service not yet/never wired, or cadence fixed at boot). Consumed
     * by the GET effective view; boot-time properties remain the restart SoT.
     */
    static final class RuntimeOverrides {
        private volatile Long usageMonitorThreshold;
        private volatile String urrMode;
        private volatile Long urrPollMs;

        Long usageMonitorThreshold() { return usageMonitorThreshold; }
        String urrMode() { return urrMode; }
        Long urrPollMs() { return urrPollMs; }

        void setUsageMonitorThreshold(Long v) { this.usageMonitorThreshold = v; }
        void setUrrMode(String v) { this.urrMode = v; }
        void setUrrPollMs(Long v) { this.urrPollMs = v; }

        void clear() {
            usageMonitorThreshold = null;
            urrMode = null;
            urrPollMs = null;
        }
    }

    // Called-out static cache names actually defined by EpcInfinispanManager.
    static final List<String> CACHE_NAMES =
            List.of("epc-sessions", "epc-subscribers", "epc-pcc");

    @ConfigProperty(name = "quarkus.http.port", defaultValue = "8099")
    int httpPort;

    // Gap-A lands epc.datastore.enabled in the same worktree; default on.
    @ConfigProperty(name = "epc.datastore.enabled", defaultValue = "true")
    boolean datastoreEnabled;

    @ConfigProperty(name = "epc.role.hss", defaultValue = "true")
    boolean roleHss;

    @ConfigProperty(name = "epc.role.sgwc", defaultValue = "true")
    boolean roleSgwc;

    @ConfigProperty(name = "epc.role.pgwc", defaultValue = "true")
    boolean rolePgwc;

    @ConfigProperty(name = "epc.role.pcrf", defaultValue = "false")
    boolean rolePcrf;

    @ConfigProperty(name = "epc.role.pcef", defaultValue = "false")
    boolean rolePcef;

    @ConfigProperty(name = "epc.role.up", defaultValue = "false")
    boolean roleUp;

    @ConfigProperty(name = "epc.diameter.peer.host")
    Optional<String> diameterPeerHost;

    @ConfigProperty(name = "epc.diameter.peer.port", defaultValue = "3868")
    int diameterPeerPort;

    @ConfigProperty(name = "epc.diameter.peer.role", defaultValue = "client")
    String diameterPeerRole;

    @ConfigProperty(name = "epc.diameter.destination.host")
    Optional<String> diameterDestHost;

    @ConfigProperty(name = "epc.diameter.destination.realm")
    Optional<String> diameterDestRealm;

    @ConfigProperty(name = "epc.diameter.origin.host")
    Optional<String> diameterOriginHost;

    @ConfigProperty(name = "epc.diameter.origin.realm", defaultValue = "epc.lab")
    String diameterOriginRealm;

    @ConfigProperty(name = "epc.diameter.transport", defaultValue = "tcp")
    String diameterTransport;

    @ConfigProperty(name = "epc.pcrf.default-qos", defaultValue = "MBB")
    String pcrfDefaultQos;

    @ConfigProperty(name = "epc.pcrf.rx.enabled", defaultValue = "true")
    boolean pcrfRxEnabled;

    @ConfigProperty(name = "epc.pcef.cdr.enabled", defaultValue = "true")
    boolean pcefCdrEnabled;

    // P1 knobs: HSS MME peer-trust drawbridge (Open5GS #4337), UP dataplane
    // (QER/URR) mode, and PCEF online charging (Gy/Gy-over-Gx). Surfaced for
    // dashboard parity; neutral defaults keep lab behavior unchanged.
    @ConfigProperty(name = "epc.hss.mme.allowlist.enabled", defaultValue = "false")
    boolean hssMmeAllowlistEnabled;

    @ConfigProperty(name = "epc.hss.mme.allowlist")
    Optional<String> hssMmeAllowlist;

    @ConfigProperty(name = "epc.up.qer.enabled", defaultValue = "false")
    boolean upQerEnabled;

    @ConfigProperty(name = "epc.up.urr.mode", defaultValue = "poll")
    String upUrrMode;

    @ConfigProperty(name = "epc.up.urr.poll-ms", defaultValue = "60000")
    long upUrrPollMs;

    @ConfigProperty(name = "epc.up.urr.baseline.persist-ms", defaultValue = "300000")
    long upUrrBaselinePersistMs;

    @ConfigProperty(name = "epc.pcef.ocs.enabled", defaultValue = "false")
    boolean pcefOcsEnabled;

    // SGW-U / PGW-U are separate from the generic PFCP heartbeat peer: Sxa and
    // Sxb target distinct GTP-U anchors on the user plane.
    @ConfigProperty(name = "epc.sgwu.peer.host", defaultValue = "127.0.0.1")
    String sgwuPeerHost;

    @ConfigProperty(name = "epc.sgwu.peer.port", defaultValue = "8805")
    int sgwuPeerPort;

    @ConfigProperty(name = "epc.pgwu.peer.host", defaultValue = "127.0.0.1")
    String pgwuPeerHost;

    @ConfigProperty(name = "epc.pgwu.peer.port", defaultValue = "8805")
    int pgwuPeerPort;

    // APN-AMBR / UE-AMBR defaults (bits/s, pilot default = 1 Gbit/s).
    @ConfigProperty(name = "epc.ambr.apn.ul-bps", defaultValue = "1000000000")
    long apnAmbrUlBps;

    @ConfigProperty(name = "epc.ambr.apn.dl-bps", defaultValue = "1000000000")
    long apnAmbrDlBps;

    @ConfigProperty(name = "epc.ambr.ue.ul-bps", defaultValue = "1000000000")
    long ueAmbrUlBps;

    @ConfigProperty(name = "epc.ambr.ue.dl-bps", defaultValue = "1000000000")
    long ueAmbrDlBps;

    // PDN address pool served by the PGW-C IPAM.
    @ConfigProperty(name = "epc.pdn.pool.ipv4", defaultValue = "10.64.0.0/16")
    String pdnPoolIpv4;

    @ConfigProperty(name = "epc.pdn.pool.ipv6", defaultValue = "2001:db8::/64")
    String pdnPoolIpv6;

    @ConfigProperty(name = "epc.cluster.mode", defaultValue = "LOCAL")
    String clusterMode;

    @ConfigProperty(name = "epc.cluster.name", defaultValue = "epc-cluster")
    String clusterName;

    @ConfigProperty(name = "epc.cluster.node-id", defaultValue = "epc-1")
    String nodeId;

    @ConfigProperty(name = "epc.cluster.node-index", defaultValue = "1")
    int nodeIndex;

    @ConfigProperty(name = "epc.gtp.peer.host", defaultValue = "127.0.0.1")
    String gtpPeerHost;

    @ConfigProperty(name = "epc.gtp.peer.port", defaultValue = "2123")
    int gtpPeerPort;

    @ConfigProperty(name = "epc.gtp.bind.port", defaultValue = "2123")
    int gtpBindPort;

    @ConfigProperty(name = "epc.pfcp.peer.host", defaultValue = "127.0.0.1")
    String pfcpPeerHost;

    @ConfigProperty(name = "epc.pfcp.peer.port", defaultValue = "8805")
    int pfcpPeerPort;

    @ConfigProperty(name = "epc.pfcp.bind.port", defaultValue = "8805")
    int pfcpBindPort;

    @ConfigProperty(name = "epc.s5c.wire", defaultValue = "false")
    boolean s5cWire;

    @ConfigProperty(name = "epc.s5c.pgwc.peer.host", defaultValue = "127.0.0.1")
    String s5cPgwcPeerHost;

    @ConfigProperty(name = "epc.s5c.pgwc.peer.port", defaultValue = "2123")
    int s5cPgwcPeerPort;

    @ConfigProperty(name = "epc.jdbc.url", defaultValue = EpcJdbc.DEFAULT_URL)
    String jdbcUrl;

    /** Secret: surfaced on the dashboard only as a redacted placeholder, never the value. */
    @ConfigProperty(name = "epc.hss.secret.kek-hex", defaultValue = "000102030405060708090a0b0c0d0e0f")
    String hssSecretKekHex;

    @ConfigProperty(name = "epc.cluster.ttl-seconds", defaultValue = "7200")
    long ttlSeconds;

    @Inject
    EpcRuntime runtime;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> config() {
        EpcRoles roles = runtime != null && runtime.bootstrap() != null
                ? runtime.bootstrap().roles()
                : EpcRoles.fromEnv(roleHss, roleSgwc, rolePgwc, rolePcrf, rolePcef, roleUp);
        // Runtime-aware values: live service state first, then volatile overrides,
        // then the boot-time properties.
        String effUrrMode = OVERRIDES.urrMode() != null ? OVERRIDES.urrMode() : upUrrMode;
        long effUrrPollMs = OVERRIDES.urrPollMs() != null ? OVERRIDES.urrPollMs() : upUrrPollMs;
        Map<String, Object> out = effective(httpPort, datastoreEnabled, roles,
                clusterMode, clusterName, nodeId, nodeIndex,
                gtpPeerHost, gtpPeerPort, gtpBindPort,
                pfcpPeerHost, pfcpPeerPort, pfcpBindPort,
                s5cWire, s5cPgwcPeerHost, s5cPgwcPeerPort,
                jdbcUrl, ttlSeconds,
                diameterPeerHost.orElse(""), diameterPeerPort, diameterPeerRole,
                diameterDestHost.orElse(""), diameterDestRealm.orElse(""),
                diameterOriginHost.orElse(""),
                diameterOriginRealm, diameterTransport,
                pcrfDefaultQos, thresholdNow(), pcrfRxEnabled, pcefCdrEnabled,
                hssMmeAllowlist.orElse(""), hssMmeAllowlistEnabled,
                upQerEnabled, effUrrMode, effUrrPollMs, upUrrBaselinePersistMs,
                pcefOcsEnabled,
                sgwuPeerHost, sgwuPeerPort, pgwuPeerHost, pgwuPeerPort,
                apnAmbrUlBps, apnAmbrDlBps, ueAmbrUlBps, ueAmbrDlBps,
                pdnPoolIpv4, pdnPoolIpv6);
        // Parity: every configurable knob appears on the dashboard; the HSS KEK is
        // a secret, so only a redacted placeholder is shown (never the key itself).
        out.put("hssSecretKekHex", entry(hssSecretKekHex == null || hssSecretKekHex.isBlank()
                ? "(unset)" : "***redacted***",
                "(boot-time) HSS key-encryption key for Ki/OPc at rest; value redacted here (epc.hss.secret.kek-hex)"));
        return out;
    }

    /** Effective usage-monitor threshold: live PCRF service, else override, else boot default. */
    private long thresholdNow() {
        EpcBootstrap bootstrap = runtime == null ? null : runtime.bootstrap();
        PcrfService svc = bootstrap == null ? null : bootstrap.pcrfService();
        if (svc != null) {
            return svc.usageMonitorThreshold();
        }
        Long override = OVERRIDES.usageMonitorThreshold();
        return override != null ? override : USAGE_MONITOR_THRESHOLD_BOOT_DEFAULT;
    }

    /**
     * Runtime PUT seam (Phase D). Accepts only {@link #MUTABLE_KEYS}; every other
     * key is boot-time only and answers 404. Body is the raw value (a JSON-quoted
     * scalar is tolerated). Success returns {@code {key, value, applied, note?}};
     * {@code applied=false} means the value was validated and stored as a volatile
     * override (served on GET) but could not be pushed into a live service.
     */
    @PUT
    @Path("{key}")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> put(@PathParam("key") String key, String body) {
        String raw = stripQuotes(body);
        EpcBootstrap bootstrap = runtime == null ? null : runtime.bootstrap();
        return switch (key) {
            case KEY_USAGE_MONITOR_THRESHOLD -> applyUsageMonitorThreshold(key, raw,
                    bootstrap == null ? null : bootstrap.pcrfService(), OVERRIDES);
            case KEY_UP_URR_MODE -> applyUrrMode(key, raw,
                    bootstrap == null ? null : bootstrap.urrPoller(), OVERRIDES);
            case KEY_UP_URR_POLL_MS -> applyUrrPollMs(key, raw, OVERRIDES);
            default -> throw error(Response.Status.NOT_FOUND, Map.of(
                    "error", "unknown or boot-time-only key: " + key,
                    "mutableKeys", MUTABLE_KEYS));
        };
    }

    static Map<String, Object> applyUsageMonitorThreshold(String key, String raw,
            PcrfService pcrf, RuntimeOverrides ov) {
        long v = parseLong(key, raw);
        if (v < 0) {
            throw error(Response.Status.BAD_REQUEST, Map.of(
                    "error", key + " must be >= 0", "value", v));
        }
        if (pcrf != null) {
            pcrf.withUsageMonitorThreshold(v);
            return ok(key, v, true, null);
        }
        ov.setUsageMonitorThreshold(v);
        return ok(key, v, false,
                "PcrfService not reachable (not booted/heap path); override stored and served on GET");
    }

    static Map<String, Object> applyUrrMode(String key, String raw,
            UrrPoller poller, RuntimeOverrides ov) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("poll") && !mode.equals("off")) {
            throw error(Response.Status.BAD_REQUEST, Map.of(
                    "error", key + " must be one of poll|off", "value", String.valueOf(raw)));
        }
        ov.setUrrMode(mode);
        if (poller != null) {
            if (mode.equals("poll")) {
                poller.start();
            } else {
                poller.stop();
            }
            return ok(key, mode, true, null);
        }
        if (mode.equals("off")) {
            return ok(key, mode, true, "no URR poller wired at boot; nothing running to stop");
        }
        return ok(key, mode, false,
                "no URR poller wired at boot (epc.up.urr.mode != poll); override stored and served on GET");
    }

    static Map<String, Object> applyUrrPollMs(String key, String raw, RuntimeOverrides ov) {
        long v = parseLong(key, raw);
        if (v < 100) {
            throw error(Response.Status.BAD_REQUEST, Map.of(
                    "error", key + " must be >= 100", "value", v));
        }
        ov.setUrrPollMs(v);
        // UrrPoller cadence is final once constructed at boot — no live reschedule seam.
        return ok(key, v, false,
                "UrrPoller cadence is boot-time final; override stored, served on GET and consumed by the next boot");
    }

    private static long parseLong(String key, String raw) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw error(Response.Status.BAD_REQUEST, Map.of(
                    "error", key + " must be a long", "value", String.valueOf(raw)));
        }
    }

    private static String stripQuotes(String body) {
        String s = body == null ? "" : body.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Map<String, Object> ok(String key, Object value, boolean applied, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("value", value);
        m.put("applied", applied);
        if (note != null) {
            m.put("note", note);
        }
        return m;
    }

    private static WebApplicationException error(Response.Status status, Map<String, Object> body) {
        return new WebApplicationException(Response.status(status)
                .entity(body)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .build());
    }

    /**
     * Pure builder — unit-testable without CDI or a live HTTP server. Every key
     * is exposed as {@code {value, writable, doc}}; boot-time keys are
     * {@code writable=false}, the three runtime-mutable keys (PCRF usage-monitor
     * threshold, URR mode, URR poll-ms) are {@code writable=true} (see class
     * javadoc). Key order stable via an outer {@link LinkedHashMap}. Hides a
     * top-level {@code effective} topology block.
     */
    static Map<String, Object> effective(int httpPort, boolean datastoreEnabled, EpcRoles roles,
            String clusterMode, String clusterName, String nodeId, int nodeIndex,
            String gtpPeerHost, int gtpPeerPort, int gtpBindPort,
            String pfcpPeerHost, int pfcpPeerPort, int pfcpBindPort,
            boolean s5cWire, String s5cPgwcPeerHost, int s5cPgwcPeerPort,
            String jdbcUrl, long ttlSeconds,
            String diameterPeerHost, int diameterPeerPort, String diameterPeerRole,
            String diameterDestHost, String diameterDestRealm, String diameterOriginHost,
            String diameterOriginRealm, String diameterTransport,
            String pcrfDefaultQos, long pcrfUsageMonitorThreshold,
            boolean pcrfRxEnabled, boolean pcefCdrEnabled,
            String hssMmeAllowlist, boolean hssMmeAllowlistEnabled,
            boolean upQerEnabled, String upUrrMode, long upUrrPollMs,
            long upUrrBaselinePersistMs, boolean pcefOcsEnabled,
            String sgwuPeerHost, int sgwuPeerPort, String pgwuPeerHost, int pgwuPeerPort,
            long apnAmbrUlBps, long apnAmbrDlBps, long ueAmbrUlBps, long ueAmbrDlBps,
            String pdnPoolIpv4, String pdnPoolIpv6) {
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("httpPort", entry(httpPort,
                "HTTP admin/health port (quarkus.http.port), EPC host :8099"));
        out.put("datastoreEnabled", entry(datastoreEnabled,
                "(boot-time) HSS Postgres datastore; DATASTORE wiring runs at boot"));
        out.put("roles", roleEntry(roles,
                "(boot-time) Diameter/GTP/PFCP roles this node runs"));
        out.put("clusterMode", entry(clusterMode,
                "(boot-time) ISPN topology: LOCAL or DIST_SYNC (epc.cluster.mode)"));
        out.put("clusterName", entry(clusterName,
                "(boot-time) ISPN cluster name; must NOT be elisa-cluster (epc.cluster.name)"));
        out.put("nodeId", entry(nodeId,
                "(boot-time) ISPN node id, unique among SGW-C/PGW-C peers (epc.cluster.node-id)"));
        out.put("nodeIndex", entry(nodeIndex,
                "(boot-time) ISPN node index 0-255 (epc.cluster.node-index)"));
        out.put("ttlSeconds", entry(ttlSeconds,
                "(boot-time) ISPN cache TTL for sessions/subscribers/pcc (epc.cluster.ttl-seconds)"));
        out.put("gtpPeerHost", entry(gtpPeerHost,
                "(boot-time) GTP-C echo/peer host the S11 watchdog targets (epc.gtp.peer.host)"));
        out.put("gtpPeerPort", entry(gtpPeerPort,
                "(boot-time) GTP-C peer port (epc.gtp.peer.port)"));
        out.put("gtpBindPort", entry(gtpBindPort,
                "(boot-time) GTP-C listen port (epc.gtp.bind.port)"));
        out.put("pfcpPeerHost", entry(pfcpPeerHost,
                "(boot-time) PFCP peer host the Sxa/Sxb heartbeat targets (epc.pfcp.peer.host)"));
        out.put("pfcpPeerPort", entry(pfcpPeerPort,
                "(boot-time) PFCP peer port (epc.pfcp.peer.port)"));
        out.put("pfcpBindPort", entry(pfcpBindPort,
                "(boot-time) PFCP listen port (epc.pfcp.bind.port)"));
        out.put("s5cWire", entry(s5cWire,
                "(boot-time) split S5-C wire mode: SGW-C -> PGW-C GTPv2-C requests (epc.s5c.wire)"));
        out.put("s5cPgwcPeerHost", entry(s5cPgwcPeerHost,
                "(boot-time) PGW-C S5-C peer host (epc.s5c.pgwc.peer.host)"));
        out.put("s5cPgwcPeerPort", entry(s5cPgwcPeerPort,
                "(boot-time) PGW-C S5-C peer port (epc.s5c.pgwc.peer.port)"));
        out.put("jdbcUrl", entry(jdbcUrl,
                "(boot-time) HSS PostgreSQL URL on postgres-epc/epc (epc.jdbc.url)"));
        out.put("jdbcScheme", entry("postgresql",
                "HSS datastore scheme, fixed Postgres for the product"));
        out.put("diameterPeerHost", entry(diameterPeerHost,
                "(boot-time) Diameter peer host the S6a/Cx/Gx/Rx watchdog targets (epc.diameter.peer.host)"));
        out.put("diameterPeerPort", entry(diameterPeerPort,
                "(boot-time) Diameter peer port (epc.diameter.peer.port)"));
        out.put("diameterPeerRole", entry(diameterPeerRole,
                "(boot-time) Diameter peer role: client|server (epc.diameter.peer.role)"));
        out.put("diameterDestHost", entry(diameterDestHost,
                "(boot-time) Diameter Destination-Host AVP; blank omits (epc.diameter.destination.host)"));
        out.put("diameterDestRealm", entry(diameterDestRealm,
                "(boot-time) Diameter Destination-Realm AVP; blank omits (epc.diameter.destination.realm)"));
        out.put("diameterOriginHost", entry(diameterOriginHost,
                "(boot-time) Diameter Origin-Host; blank -> nodeId.epc.lab (epc.diameter.origin.host)"));
        out.put("diameterOriginRealm", entry(diameterOriginRealm,
                "(boot-time) Diameter origin realm sent on CER (epc.diameter.origin.realm)"));
        out.put("diameterTransport", entry(diameterTransport,
                "(boot-time) Diameter transport: tcp (or sctp when the stack enables it)"));
        out.put("pcrfDefaultQos", entry(pcrfDefaultQos,
                "(boot-time) PCRF default QoS class when no PccRule matches (epc.pcrf.default-qos)"));
        out.put("pcrfUsageMonitorThreshold", mutableEntry(pcrfUsageMonitorThreshold,
                "(runtime) PCRF Usage-Monitoring volume threshold in bytes "
                + "(PUT " + KEY_USAGE_MONITOR_THRESHOLD + ", long >= 0; live on PcrfService)"));
        out.put("pcrfRxEnabled", entry(pcrfRxEnabled,
                "(boot-time) Rx (AAR/RAR/STR) application routing on the host gateway (epc.pcrf.rx.enabled)"));
        out.put("pcefCdrEnabled", entry(pcefCdrEnabled,
                "(boot-time) PCEF generates CDRs into the Postgres datastore (epc.pcef.cdr.enabled)"));
        out.put("hssMmeAllowlistEnabled", entry(hssMmeAllowlistEnabled,
                "(boot-time) MME peer-trust drawbridge: reject S6a from non-allowlisted peers (epc.hss.mme.allowlist.enabled)"));
        out.put("hssMmeAllowlist", entry(hssMmeAllowlist,
                "(boot-time) comma list of allowed MME Origin-Host/Realm/PLMN on S6a; blank = trust all (epc.hss.mme.allowlist)"));
        out.put("upQerEnabled", entry(upQerEnabled,
                "(boot-time) UP applies per-bearer QER (QoS/HTB) on the GTP-U dataplane (epc.up.qer.enabled)"));
        out.put("upUrrMode", mutableEntry(upUrrMode,
                "(runtime) UP Usage Report Rule mode: poll|off "
                + "(PUT " + KEY_UP_URR_MODE + "; applied live by start/stop of the boot-wired UrrPoller)"));
        out.put("upUrrPollMs", mutableEntry(upUrrPollMs,
                "(runtime-requested) UP URR poll cadence ms when mode=poll "
                + "(PUT " + KEY_UP_URR_POLL_MS + ", >= 100; UrrPoller cadence is boot-time final, applied at next boot)"));
        out.put("upUrrBaselinePersistMs", entry(upUrrBaselinePersistMs,
                "(boot-time) UP persist baseline URR counters to Postgres every N ms (epc.up.urr.baseline.persist-ms)"));
        out.put("pcefOcsEnabled", entry(pcefOcsEnabled,
                "(boot-time) PCEF online charging to OCS over Gy (async CCR->CCA) (epc.pcef.ocs.enabled)"));
        out.put("sgwuPeerHost", entry(sgwuPeerHost,
                "(boot-time) SGW-U Sxa PFCP peer host (epc.sgwu.peer.host)"));
        out.put("sgwuPeerPort", entry(sgwuPeerPort,
                "(boot-time) SGW-U Sxa PFCP peer port (epc.sgwu.peer.port)"));
        out.put("pgwuPeerHost", entry(pgwuPeerHost,
                "(boot-time) PGW-U Sxb PFCP peer host (epc.pgwu.peer.host)"));
        out.put("pgwuPeerPort", entry(pgwuPeerPort,
                "(boot-time) PGW-U Sxb PFCP peer port (epc.pgwu.peer.port)"));
        out.put("ambr", entry(Map.of(
                        "apn", Map.of("ulBps", apnAmbrUlBps, "dlBps", apnAmbrDlBps),
                        "ue", Map.of("ulBps", ueAmbrUlBps, "dlBps", ueAmbrDlBps)),
                "(boot-time) APN-AMBR / UE-AMBR defaults in bits/s (epc.ambr.*)"));
        out.put("pdnPool", entry(Map.of(
                        "ipv4", pdnPoolIpv4,
                        "ipv6", pdnPoolIpv6),
                "(boot-time) PGW-C IPAM PDN pool (epc.pdn.pool.*)"));

        Map<String, Object> topology = effectiveTopology(clusterMode, roles,
                s5cWire, s5cPgwcPeerHost, s5cPgwcPeerPort,
                upQerEnabled, upUrrMode, upUrrPollMs, upUrrBaselinePersistMs);
        topology.put("writable", false);
        topology.put("doc",
                "(boot-time) effective ISPN cache names + Diameter applications the node serves");
        out.put("effective", topology);
        return out;
    }

    private static Map<String, Object> entry(Object value, String doc) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("value", value);
        e.put("writable", false);
        e.put("doc", doc);
        return e;
    }

    /** Runtime-mutable knob (PUT /epc/config/{key}); writable=true on the dashboard. */
    private static Map<String, Object> mutableEntry(Object value, String doc) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("value", value);
        e.put("writable", true);
        e.put("doc", doc);
        return e;
    }

    private static Map<String, Object> roleEntry(EpcRoles roles, String doc) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hss", roles.hss());
        value.put("sgwc", roles.sgwc());
        value.put("pgwc", roles.pgwc());
        value.put("pcrf", roles.pcrf());
        value.put("pcef", roles.pcef());
        value.put("up", roles.up());
        return entry(value, doc);
    }

    /**
     * Canonical effective topology: ISPN mode, the caches this host defines,
     * and the 3GPP Diameter applications it is prepared to answer. Gx/Rx are
     * routed via the host gateway {@code PcrfPort} seam (CCR→CCA, AAR→AAA,
     * RAR→RAA, STR→STA) delegating to {@code PcrfSbb} on {@code diameter-ra}.
     */
    private static Map<String, Object> effectiveTopology(String clusterMode, EpcRoles roles,
            boolean s5cWire, String s5cPgwcPeerHost, int s5cPgwcPeerPort,
            boolean upQerEnabled, String upUrrMode, long upUrrPollMs,
            long upUrrBaselinePersistMs) {
        Map<String, Object> eff = new LinkedHashMap<>();
        eff.put("isptMode", clusterMode);
        eff.put("cacheNames", CACHE_NAMES);
        Map<String, Object> apps = new LinkedHashMap<>();
        apps.put("s6a", Map.of(
                "routed", roles.wireS6a(),
                "commands", List.of("AIR->AIA 2001", "ULR->ULA 2001", "PUR->PUA", "CLR->CLA")));
        apps.put("cx", Map.of(
                "routed", roles.hss(),
                "commands", List.of("UAR/LIR->SAA/CIA", "SAR", "MAR")));
        apps.put("gx", Map.of(
                "routed", roles.wirePcrf() || roles.wirePcef() || roles.wirePcefStandalone(),
                "commands", List.of("CCR->CCA")));
        apps.put("rx", Map.of(
                "routed", roles.wirePcrf(),
                "commands", List.of("AAR->AAA", "RAR->RAA", "STR->STA")));
        eff.put("applications", apps);
        eff.put("gxRx", Map.of(
                "routedGx", roles.wirePcrf() || roles.wirePcef() || roles.wirePcefStandalone(),
                "routedRx", roles.wirePcrf(),
                "note", "host gateway PcrfPort -> PcrfSbb on diameter-ra"));
        eff.put("s5c", Map.of(
                "wireMode", s5cWire,
                "mode", s5cWire ? "wire (split SGW-C -> PGW-C GTPv2-C)" : "in-process (lab embedded)",
                "pgwcPeerHost", s5cPgwcPeerHost,
                "pgwcPeerPort", s5cPgwcPeerPort));
        eff.put("userPlane", Map.of(
                "roles", roles.up(),
                "qerEnabled", upQerEnabled,
                "urrMode", upUrrMode,
                "urr", Map.of(
                        "pollMs", upUrrPollMs,
                        "baselinePersistMs", upUrrBaselinePersistMs)));
        return eff;
    }
}