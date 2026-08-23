package et.epc.pgwc;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import et.epc.api.Imsi;
import et.epc.api.QosProfile;
import et.epc.pcef.PccRule;
import et.epc.pcef.PcrfClient;
import et.epc.pcef.PolicyDecision;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Gx wire seam (Phase C): PCEF→PCRF over Diameter, app 16777238 (TS 29.212),
 * CCR/CCA command 272. Implements {@link PcrfClient} for
 * {@code PcefService.beginGxSession}; the CCR goes out on the injected
 * {@code diameter-ra} command port and the CCA comes back as a
 * {@link DiameterAnswerEvent} routed into {@link #onCca(DiameterAnswerEvent)}
 * (the epc-host event-routing owner wires that seam).
 *
 * <p><b>Peer truth:</b> a CCR is only emitted in {@code wire} mode
 * ({@link GxWireConfig#wire()}) <i>and</i> when the supplied
 * {@code isPeerReady} supplier is true (CER/CEA accepted) — never on "socket
 * bound". On {@code !live}, timeout, transport failure or a non-2001 CCA the
 * client falls back to the static decision (fail-open policy path; prepaid
 * fail-closed stays on {@code PcefService.open} — the Gy gate — matching
 * {@code PcefService} semantics). {@link #beginGxSession} never throws.</p>
 *
 * <p><b>Flat AVP contract</b> mirrors {@code et.epc.pcrf.GxHandler}
 * (documented there): request keys IMSI=444, APN=30, UE-IP=8, charging-id=2,
 * request type=416; answer keys Result-Code=268, Charging-Rule-Install=1001
 * as pipe-joined {@code name=..;qci=..;arp=p,c,v;mbr=ul,dl;gbr=ul,dl;
 * ratingGroup=r|_;serviceId=s|_;flow=..;gating=true|false} rules,
 * Default-EPS-Bearer-QoS=1023 as
 * {@code qci=..;arp=p,c,v;apnAmbr=ul,dl;ueAmbr=ul,dl}. Structured
 * Charging-Rule-Install grouped AVPs are parsed as a fallback when the flat
 * map is empty.</p>
 *
 * <p><b>Hot path:</b> {@link #beginGxSession} blocks up to
 * {@link GxWireConfig#ccrTimeoutMs()} on the CCA; keep the timeout tight.</p>
 */
public final class WirePcrfClient implements PcrfClient {

    /** Gx Diameter application id (TS 29.212). */
    public static final long GX_APP_ID = 16_777_238L;
    /** Credit-Control-Request/Answer command code (Gx CCR/CCA). */
    public static final int CCR_CMD = 272;

    /** CC-Request-Type INITIAL (TS 29.212 §5.3.23). */
    public static final String REQ_TYPE_INITIAL = "1";

    // Flat AVP codes — request side (CCR).
    static final int CHARGING_ID = 2;
    static final int FRAMED_IP_ADDRESS = 8;
    static final int CALLED_STATION_ID = 30;
    static final int RESULT_CODE = 268;
    static final int AUTH_SESSION_STATE = 277;      // NO_STATE_MAINTAINED = 1
    static final int CC_REQUEST_NUMBER = 415;
    static final int CC_REQUEST_TYPE = 416;
    static final int SUBSCRIPTION_ID_DATA = 444;
    static final int SUBSCRIPTION_ID_TYPE = 450;    // END-USER-IMSI = 1
    // Flat AVP codes — answer side (CCA), plus structured group codes.
    static final int CHARGING_RULE_INSTALL = 1001;
    static final int CHARGING_RULE_DEFINITION = 1003;
    static final int CHARGING_RULE_NAME = 1005;
    static final int DEFAULT_EPS_BEARER_QOS = 1023;
    static final int QOS_INFORMATION = 1016;
    static final int QOS_CLASS_IDENTIFIER = 1028;
    static final int MAX_REQUESTED_BANDWIDTH_UL = 516;
    static final int MAX_REQUESTED_BANDWIDTH_DL = 517;
    static final int GUARANTEED_BITRATE_UL = 518;
    static final int GUARANTEED_BITRATE_DL = 519;
    static final int ALLOCATION_RETENTION_PRIORITY = 1034;
    static final int PRIORITY_LEVEL = 1046;
    static final int PRE_EMPTION_CAPABILITY = 1047;
    static final int PRE_EMPTION_VULNERABILITY = 1048;
    static final int RATING_GROUP = 432;
    static final int SERVICE_IDENTIFIER = 439;
    static final int FLOW_INFORMATION = 1058;
    static final int FLOW_DESCRIPTION = 507;
    static final int FLOW_STATUS = 511;

    static final long RESULT_SUCCESS = 2001L;

    private static final Logger LOG = LogManager.getLogger(WirePcrfClient.class);
    private static final String DEFAULT_ORIGIN_HOST = "epc-pgwc-1.epc.lab";

    private final RaCommandPort sender;
    private final BooleanSupplier peerReady;
    private final GxWireConfig config;
    private final String originHost;
    private final ConcurrentHashMap<String, CompletableFuture<DiameterAnswerEvent>> pending =
            new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(1);

    public WirePcrfClient(RaCommandPort sender, BooleanSupplier peerReady) {
        this(sender, peerReady, GxWireConfig.fromEnv(), DEFAULT_ORIGIN_HOST);
    }

    public WirePcrfClient(RaCommandPort sender, BooleanSupplier peerReady, GxWireConfig config) {
        this(sender, peerReady, config, DEFAULT_ORIGIN_HOST);
    }

    public WirePcrfClient(RaCommandPort sender, BooleanSupplier peerReady,
                          GxWireConfig config, String originHost) {
        this.sender = Objects.requireNonNull(sender, "diameter command port");
        this.peerReady = Objects.requireNonNull(peerReady, "isPeerReady supplier");
        this.config = Objects.requireNonNull(config);
        this.originHost = (originHost == null || originHost.isBlank())
                ? DEFAULT_ORIGIN_HOST : originHost.trim();
    }

    /**
     * CCR for one IP-CAN session; blocks up to the configured CCA timeout.
     * Never throws — {@code !wire}, {@code !peerReady}, timeout, transport
     * errors and non-2001 answers all fall back to the static decision
     * (fail-open; prepaid fail-closed is enforced earlier by
     * {@code PcefService.open}).
     */
    @Override
    public PolicyDecision beginGxSession(Imsi imsi, String apn, String ueIpv4,
                                         long chargingId, String requestType) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        if (!config.wire()) {
            return staticByApn(apn);
        }
        if (!peerReady.getAsBoolean()) {
            LOG.debug("Gx CCR skipped imsi={} apn={}: peer not CEA-ready", imsi, apn);
            return staticByApn(apn);
        }
        String sessionId = originHost + ";gx-wire;" + seq.getAndIncrement();
        CompletableFuture<DiameterAnswerEvent> cca = new CompletableFuture<>();
        pending.put(sessionId, cca);
        try {
            sender.sendCommand(new SendDiameterRequest(sessionId, GX_APP_ID, CCR_CMD,
                    config.peerHost(), config.destRealm(),
                    ccrAvps(imsi, apn, ueIpv4, chargingId, requestType), List.of()));
            DiameterAnswerEvent answer = cca.get(config.ccrTimeoutMs(), TimeUnit.MILLISECONDS);
            PolicyDecision decision = toDecision(apn, answer);
            if (decision == null) {
                LOG.warn("Gx CCA unusable sid={} rc={} — static fallback",
                        sessionId, answer.resultCode());
                return staticByApn(apn);
            }
            return decision;
        } catch (TimeoutException e) {
            LOG.warn("Gx CCA timeout sid={} after {}ms — static fallback",
                    sessionId, config.ccrTimeoutMs());
            return staticByApn(apn);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return staticByApn(apn);
        } catch (ExecutionException e) {
            LOG.warn("Gx CCA delivery failed sid={}: {} — static fallback",
                    sessionId, e.toString());
            return staticByApn(apn);
        } catch (RuntimeException e) {
            LOG.warn("Gx CCR/CCA failed sid={}: {} — static fallback", sessionId, e.toString());
            return staticByApn(apn);
        } finally {
            pending.remove(sessionId);
        }
    }

    /**
     * Inbound CCA correlate: completes the pending CCR future for the Gx
     * Session-Id. Returns {@code true} when the answer matched a pending
     * request (app 16777238, cmd 272).
     */
    public boolean onCca(DiameterAnswerEvent answer) {
        if (answer == null || answer.applicationId() != GX_APP_ID
                || answer.commandCode() != CCR_CMD) {
            return false;
        }
        CompletableFuture<DiameterAnswerEvent> future = pending.remove(answer.sessionId());
        if (future == null) {
            return false;
        }
        future.complete(answer);
        return true;
    }

    /** Flat CCR body per the GxHandler contract (single-valued string map). */
    static Map<Integer, String> ccrAvps(Imsi imsi, String apn, String ueIpv4,
                                        long chargingId, String requestType) {
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        avps.put(SUBSCRIPTION_ID_DATA, imsi.digits());
        avps.put(SUBSCRIPTION_ID_TYPE, "1");
        avps.put(CALLED_STATION_ID, apn);
        if (ueIpv4 != null && !ueIpv4.isBlank()) {
            avps.put(FRAMED_IP_ADDRESS, ueIpv4.trim());
        }
        if (chargingId > 0) {
            avps.put(CHARGING_ID, Long.toString(chargingId));
        }
        avps.put(CC_REQUEST_TYPE,
                requestType == null || requestType.isBlank()
                        ? REQ_TYPE_INITIAL : requestType.trim());
        avps.put(CC_REQUEST_NUMBER, "1");
        avps.put(AUTH_SESSION_STATE, "1");
        return Map.copyOf(avps);
    }

    /**
     * CCA → {@link PolicyDecision}; {@code null} when the answer is unusable
     * (non-2001 or unparsable rules) so the caller falls back to static.
     */
    static PolicyDecision toDecision(String apn, DiameterAnswerEvent cca) {
        long rc = cca.resultCode();
        Map<Integer, String> avps = cca.avps() == null ? Map.of() : cca.avps();
        String flatRc = avps.get(RESULT_CODE);
        if (flatRc != null && !flatRc.isBlank()) {
            try {
                rc = Long.parseLong(flatRc.trim());
            } catch (NumberFormatException ignored) {
                // keep the event-level result code
            }
        }
        if (rc != RESULT_SUCCESS) {
            return null;
        }
        try {
            List<PccRule> rules = parseRules(avps.get(CHARGING_RULE_INSTALL));
            if (rules.isEmpty()) {
                rules = parseStructuredRules(cca.avpsStructured());
            }
            QosProfile qos = parseDefaultBearerQos(avps.get(DEFAULT_EPS_BEARER_QOS));
            String ruleName = rules.isEmpty() ? "gx-wire-none" : rules.get(0).name();
            boolean gatingOpen = rules.isEmpty() || rules.get(0).gatingOpen();
            return new PolicyDecision(qos, gatingOpen, ruleName, rules);
        } catch (RuntimeException e) {
            LOG.warn("Gx CCA parse failed apn={}: {}", apn, e.toString());
            return null;
        }
    }

    /** Static fallback — mirrors {@code PcefService.staticByApn} (fail-open). */
    static PolicyDecision staticByApn(String apn) {
        return "ims".equalsIgnoreCase(apn) ? PolicyDecision.ims() : PolicyDecision.staticDefault();
    }

    /** Pipe-joined flat rule encoding (GxHandler.encodeRules contract). */
    static List<PccRule> parseRules(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<PccRule> out = new ArrayList<>();
        for (String token : encoded.split("\\|")) {
            if (!token.isBlank()) {
                out.add(parseRule(token));
            }
        }
        return List.copyOf(out);
    }

    private static PccRule parseRule(String token) {
        Map<String, String> kv = splitKv(token);
        int[] arp = parseTriple(kv.get("arp"), 8, 0, 1);
        long[] mbr = parsePair(kv.get("mbr"), 1_000_000_000L);
        long[] gbr = parsePair(kv.get("gbr"), 0L);
        return new PccRule(
                kv.getOrDefault("name", "gx-unknown"),
                parseInt(kv.get("qci"), 9),
                arp[0], arp[1] != 0, arp[2] != 0,
                mbr[0], mbr[1], gbr[0], gbr[1],
                parseNullableInt(kv.get("ratingGroup")),
                dashToNull(kv.get("serviceId")),
                kv.getOrDefault("flow", ""),
                Boolean.parseBoolean(kv.getOrDefault("gating", "true")));
    }

    /** Default-EPS-Bearer-QoS flat string → QosProfile (pilot default on miss). */
    static QosProfile parseDefaultBearerQos(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return QosProfile.pilotDefault();
        }
        Map<String, String> kv = splitKv(encoded);
        int[] arp = parseTriple(kv.get("arp"), 8, 0, 1);
        long[] apnAmbr = parsePair(kv.get("apnAmbr"), 1_000_000_000L);
        long[] ueAmbr = parsePair(kv.get("ueAmbr"), 1_000_000_000L);
        return new QosProfile(parseInt(kv.get("qci"), 9), arp[0], arp[1] != 0, arp[2] != 0,
                apnAmbr[0], apnAmbr[1], ueAmbr[0], ueAmbr[1]);
    }

    private static Map<String, String> splitKv(String encoded) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : encoded.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                kv.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        return kv;
    }

    /** Structured Charging-Rule-Install(1001) fallback parse. */
    static List<PccRule> parseStructuredRules(List<DiameterAvp> avps) {
        DiameterAvp install = find(avps, CHARGING_RULE_INSTALL);
        if (install == null) {
            return List.of();
        }
        List<PccRule> out = new ArrayList<>();
        for (DiameterAvp def : children(install)) {
            if (def.code() == CHARGING_RULE_DEFINITION) {
                out.add(parseStructuredRule(def));
            }
        }
        return List.copyOf(out);
    }

    private static PccRule parseStructuredRule(DiameterAvp def) {
        List<DiameterAvp> body = children(def);
        String name = utf8(find(body, CHARGING_RULE_NAME));
        int qci = 9, prio = 8;
        boolean cap = false, vuln = true;
        long mbrUl = 1_000_000_000L, mbrDl = 1_000_000_000L, gbrUl = 0L, gbrDl = 0L;
        DiameterAvp qos = find(body, QOS_INFORMATION);
        if (qos != null) {
            List<DiameterAvp> q = children(qos);
            qci = (int) u32(find(q, QOS_CLASS_IDENTIFIER), 9L);
            mbrUl = u32(find(q, MAX_REQUESTED_BANDWIDTH_UL), mbrUl);
            mbrDl = u32(find(q, MAX_REQUESTED_BANDWIDTH_DL), mbrDl);
            gbrUl = u32(find(q, GUARANTEED_BITRATE_UL), 0L);
            gbrDl = u32(find(q, GUARANTEED_BITRATE_DL), 0L);
            DiameterAvp arp = find(q, ALLOCATION_RETENTION_PRIORITY);
            if (arp != null) {
                List<DiameterAvp> a = children(arp);
                prio = (int) u32(find(a, PRIORITY_LEVEL), 8L);
                cap = u32(find(a, PRE_EMPTION_CAPABILITY), 0L) == 1L;
                vuln = u32(find(a, PRE_EMPTION_VULNERABILITY), 0L) == 1L;
            }
        }
        Integer ratingGroup = null;
        DiameterAvp rg = find(body, RATING_GROUP);
        if (rg != null) {
            ratingGroup = (int) rg.asUnsigned32();
        }
        String serviceId = null;
        DiameterAvp si = find(body, SERVICE_IDENTIFIER);
        if (si != null) {
            serviceId = Long.toString(si.asUnsigned32());
        }
        String flow = "";
        boolean gating = true;
        DiameterAvp flowInfo = find(body, FLOW_INFORMATION);
        if (flowInfo != null) {
            List<DiameterAvp> f = children(flowInfo);
            DiameterAvp fd = find(f, FLOW_DESCRIPTION);
            if (fd != null && fd.asOctets() != null) {
                flow = new String(fd.asOctets(), StandardCharsets.UTF_8);
            }
            DiameterAvp fs = find(f, FLOW_STATUS);
            if (fs != null) {
                gating = fs.asUnsigned32() == 1L;
            }
        }
        return new PccRule(name == null || name.isBlank() ? "gx-unknown" : name,
                qci, prio, cap, vuln, mbrUl, mbrDl, gbrUl, gbrDl,
                ratingGroup, serviceId, flow, gating);
    }

    // ---- small AVP helpers --------------------------------------------------

    private static DiameterAvp find(List<DiameterAvp> avps, int code) {
        if (avps == null) {
            return null;
        }
        for (DiameterAvp avp : avps) {
            if (avp == null) {
                continue;
            }
            if (avp.code() == code) {
                return avp;
            }
            DiameterAvp nested = find(avp.children(), code);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static List<DiameterAvp> children(DiameterAvp avp) {
        return avp == null || avp.children() == null ? List.of() : avp.children();
    }

    private static long u32(DiameterAvp avp, long def) {
        return avp == null ? def : avp.asUnsigned32();
    }

    private static String utf8(DiameterAvp avp) {
        return avp == null ? null : avp.asUtf8();
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Integer parseNullableInt(String s) {
        if (s == null || s.isBlank() || "-".equals(s.trim())) {
            return null;
        }
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String dashToNull(String s) {
        return s == null || s.isBlank() || "-".equals(s.trim()) ? null : s.trim();
    }

    private static int[] parseTriple(String s, int d0, int d1, int d2) {
        int[] out = {d0, d1, d2};
        if (s == null || s.isBlank()) {
            return out;
        }
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(3, parts.length); i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // keep default component
            }
        }
        return out;
    }

    private static long[] parsePair(String s, long def) {
        long[] out = {def, def};
        if (s == null || s.isBlank()) {
            return out;
        }
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException ignored) {
                // keep default component
            }
        }
        return out;
    }

    // ---- status accessors -----------------------------------------------------

    public GxWireConfig config() {
        return config;
    }

    public boolean wireEnabled() {
        return config.wire();
    }

    public boolean peerReady() {
        return peerReady.getAsBoolean();
    }

    /** In-flight CCR count (status/test observability). */
    public int pendingCount() {
        return pending.size();
    }
}