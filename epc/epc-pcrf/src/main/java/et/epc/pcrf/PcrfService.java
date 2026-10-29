package et.epc.pcrf;

import et.epc.api.QosProfile;
import java.util.List;
import java.util.Objects;

/**
 * PCRF core: policy lookup for an IP-CAN session keyed by {@code (imsi, apn)}.
 * Decisions are cache-first (read-through {@link PccCache}) with the durable
 * rule set living in {@link PolicyRepository}; no per-packet DB writes.
 */
public final class PcrfService {

    /** Pilot monitoring key used on every Gx Usage-Monitoring-Information AVP. */
    public static final String MONITORING_KEY = "pilot-volume";

    private final PccCache cache;
    private final PolicyRepository repo;
    private volatile long usageMonitorThreshold = 1_000_000_000L; // 1 GB pilot default

    public PcrfService(PccCache cache) {
        this(cache, null);
    }

    public PcrfService(PccCache cache, PolicyRepository repo) {
        this.cache = Objects.requireNonNull(cache);
        this.repo = repo;
    }

    /** Embedded standalone default: in-heap read-through cache with no durable repo. */
    public static PcrfService withHeapPolicy() {
        return new PcrfService(new HeapPccCache());
    }

    public PccDecision decide(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        return new PccDecision(cache.rulesFor(imsi, apn),
                usageMonitorThreshold, defaultBearQos(apn));
    }

    public PcrfService withUsageMonitorThreshold(long thresholdBytes) {
        if (thresholdBytes < 0) {
            throw new IllegalArgumentException("threshold must be >= 0");
        }
        this.usageMonitorThreshold = thresholdBytes;
        return this;
    }

    public long usageMonitorThreshold() {
        return usageMonitorThreshold;
    }

    /** Persist the decision snapshot beyond the cache; repo may be null in tests. */
    public void persistDecision(String sessionId, String imsi, String apn,
                                String ueIpv4, long chargingId, PccDecision decision) {
        if (repo == null) {
            return;
        }
        repo.upsertIpCanSession(sessionId, imsi, apn, ueIpv4, chargingId,
                GxHandler.toDecisionJson(decision));
    }

    /**
     * Active Gx Session-Ids for {@code (imsi, apn)} from the durable store —
     * the RAR fan-out target list on an admin policy change. Empty when no
     * durable repo is wired (heap/lab path).
     */
    public List<String> activeGxSessionIds(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        if (repo == null) {
            return List.of();
        }
        List<String> ids = repo.ipCanSessionIds(imsi, apn);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /**
     * Admin policy-change hook (Phase C3): invalidate the cached rule set for
     * {@code (imsi, apn)} — ISPN-dist when the host wired Infinispan — and
     * return the fresh decision re-read through the durable store. The caller
     * (EpcConfigResource owner) writes {@code epc_pcc_rule} first, then invokes
     * this and pushes RAR via {@code PcrfSbb.pushRarOnPolicyChange}.
     */
    /**
     * Admin rule write (TS 29.212 §4.5.2 PCRF-initiated provisioning): persist the PCC rule
     * for {@code apn} and return the IMSIs with IP-CAN sessions on that APN — the caller
     * re-decides and pushes RAR for each via {@code PcrfSbb.pushRarOnPolicyChange}.
     */
    public List<String> updateRule(String apn, PccRule rule) {
        Objects.requireNonNull(apn);
        Objects.requireNonNull(rule);
        if (repo == null) {
            throw new IllegalStateException("no durable policy repository wired");
        }
        repo.upsertRule(apn, rule);
        return repo.imsisWithSessions(apn);
    }

    public PccDecision policyChanged(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        cache.invalidate(imsi, apn);
        return decide(imsi, apn);
    }

    static QosProfile defaultBearQos(String apn) {
        if ("ims".equalsIgnoreCase(apn)) {
            long g = 1_000_000_000L;
            return new QosProfile(5, 1, true, false, g, g, g, g);
        }
        return QosProfile.pilotDefault();
    }

    /** Convenience used by tests and the Rx/Gx handlers to source a decision. */
    public boolean isEmpty(String imsi, String apn) {
        List<PccRule> rules = cache.rulesFor(imsi, apn);
        return rules.isEmpty();
    }
}