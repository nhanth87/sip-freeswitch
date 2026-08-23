package et.epc.pcef;

import et.epc.api.Imsi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PCEF: Gx policy via injected {@link PcrfClient} when {@code pcrfLive}
 * (static fallback otherwise), Gy/Gz sessions correlated by charging-id.
 * Fail-closed for prepaid when OCS is required; fail-open for postpaid offline.
 */
public final class PcefService {

    public enum Product { POSTPAID, PREPAID }

    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, ChargingSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, List<String>> sessionRules = new ConcurrentHashMap<>();
    private final List<Cdr> cdrs = new ArrayList<>();
    private final CdrStore store;
    private volatile boolean ocsLive;
    private volatile boolean pcrfLive;
    private volatile PcrfClient pcrfClient;

    public PcefService() {
        this.store = null;
    }

    /** Postgres-backed production path: CDRs are durable, not heap. */
    public PcefService(CdrStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public ChargingId open(Imsi imsi, String apn, Product product) {
        Objects.requireNonNull(imsi);
        if (product == Product.PREPAID && !ocsLive) {
            throw new IllegalStateException("Gy OCS not live — fail-closed for prepaid");
        }
        ChargingId id = new ChargingId(ids.getAndIncrement());
        Instant start = Instant.now();
        sessions.put(id.value(), new ChargingSession(id, imsi, apn, product, start));
        if (store != null) {
            store.start(new Cdr(id, imsi, apn, 0, 0, start, start, Cdr.Kind.START));
        }
        return id;
    }

    public PolicyDecision authorize(String apn) {
        return staticByApn(apn);
    }

    /**
     * Policy decision for a specific IP-CAN session. Consults the injected Gx
     * {@link PcrfClient} only when {@code pcrfLive}; static fallback otherwise.
     */
    public PolicyDecision authorize(Imsi imsi, String apn) {
        Objects.requireNonNull(imsi);
        PcrfClient client = pcrfClient;
        if (pcrfLive && client != null) {
            return client.beginGxSession(imsi, apn, null, 0L, null);
        }
        return staticByApn(apn);
    }

    /** Gx client path: establish an IP-CAN session (CCR-INITIAL → CCA). */
    public PolicyDecision beginGxSession(Imsi imsi, String apn, String ueIpv4,
                                         long chargingId, String requestType) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        PcrfClient client = pcrfClient;
        if (pcrfLive && client != null) {
            return client.beginGxSession(imsi, apn, ueIpv4, chargingId, requestType);
        }
        return staticByApn(apn);
    }

    private static PolicyDecision staticByApn(String apn) {
        return "ims".equalsIgnoreCase(apn) ? PolicyDecision.ims() : PolicyDecision.staticDefault();
    }

    public void reportUsage(UsageRecord usage) {
        ChargingSession s = sessions.get(usage.chargingId().value());
        if (s == null) {
            throw new IllegalStateException("no charging session " + usage.chargingId().value());
        }
        s.add(usage.uplinkOctets(), usage.downlinkOctets());
        if (store != null) {
            store.interim(s.toCdr(Instant.now(), Cdr.Kind.INTERIM));
        }
    }

    public Cdr close(ChargingId id) {
        ChargingSession s = sessions.remove(id.value());
        if (s == null) {
            throw new IllegalStateException("no charging session");
        }
        Cdr cdr = s.toCdr(Instant.now(), Cdr.Kind.STOP);
        if (store != null) {
            store.persist(cdr);
        } else {
            synchronized (cdrs) {
                cdrs.add(cdr);
            }
        }
        return cdr;
    }

    public boolean reconcile(UsageRecord up, Cdr cdr) {
        return up.chargingId().equals(cdr.chargingId())
                && up.uplinkOctets() == cdr.uplinkOctets()
                && up.downlinkOctets() == cdr.downlinkOctets();
    }

    /** Record PCC rules installed by a server-initiated Gx RAR (Charging-Rule-Install). */
    public void installRules(Long chargingId, List<String> ruleNames) {
        if (chargingId == null) {
            return;
        }
        sessionRules.compute(chargingId, (k, v) -> {
            List<String> merged = new ArrayList<>(v == null ? List.of() : v);
            for (String name : ruleNames) {
                if (name != null && !name.isBlank() && !merged.contains(name)) {
                    merged.add(name);
                }
            }
            return List.copyOf(merged);
        });
    }

    /** Remove PCC rules named by a server-initiated Gx RAR (Charging-Rule-Remove). */
    public void removeRules(Long chargingId, List<String> ruleNames) {
        if (chargingId == null) {
            return;
        }
        sessionRules.compute(chargingId, (k, v) -> {
            List<String> remaining = new ArrayList<>(v == null ? List.of() : v);
            remaining.removeIf(ruleNames::contains);
            return List.copyOf(remaining);
        });
    }

    /** Rules currently installed on the charging session by server-initiated RARs. */
    public List<String> rulesFor(Long chargingId) {
        if (chargingId == null) {
            return List.of();
        }
        return sessionRules.getOrDefault(chargingId, List.of());
    }

    public void setOcsLive(boolean live) {
        this.ocsLive = live;
    }

    public void setPcrfLive(boolean live) {
        this.pcrfLive = live;
    }

    public void setPcrfClient(PcrfClient client) {
        this.pcrfClient = client;
    }

    public PcrfClient pcrfClient() {
        return pcrfClient;
    }

    public boolean ocsLive() {
        return ocsLive;
    }

    public boolean pcrfLive() {
        return pcrfLive;
    }

    public List<Cdr> cdrs() {
        if (store != null) {
            return store.list();
        }
        synchronized (cdrs) {
            return List.copyOf(cdrs);
        }
    }

    /** Charging session correlate used by the PCEF SBB to build a Gy CCR-i. */
    public record SessionContext(Imsi imsi, String apn, ChargingId chargingId) {}

    public SessionContext sessionContext(ChargingId id) {
        ChargingSession s = sessions.get(id.value());
        if (s == null) {
            throw new IllegalStateException("no charging session " + id.value());
        }
        return new SessionContext(s.imsi, s.apn, s.id);
    }

    /** Apply the granted quota from a Gy CCA; {@code ChargingId.NONE} or an unknown session is ignored. */
    public void applyGrant(ChargingId id, long grantedOctets) {
        if (id == null || id.value() == ChargingId.NONE.value()) {
            return;
        }
        ChargingSession s = sessions.get(id.value());
        if (s == null) {
            return;
        }
        s.setGrantedOctets(grantedOctets);
    }

    public Long grantedOctets(ChargingId id) {
        ChargingSession s = sessions.get(id.value());
        return s == null ? null : s.grantedOctets();
    }

    /**
     * Gy overrun guard — pure read, no OCS transport. True only when a quota is
     * configured for the session ({@code grantedBytes > 0}; offline charging with
     * {@code epc.pcef.ocs.enabled=false} passes 0) and the accumulated reported
     * volume has reached it. Unknown sessions and {@link ChargingId#NONE} are
     * never over quota, so callers can log/skip without fail-closed surprises.
     */
    public boolean isOverGrantedQuota(ChargingId id, long grantedBytes) {
        if (id == null || id.value() == ChargingId.NONE.value() || grantedBytes <= 0) {
            return false;
        }
        ChargingSession s = sessions.get(id.value());
        return s != null && s.totalOctets() >= grantedBytes;
    }

    private static final class ChargingSession {
        private final ChargingId id;
        private final Imsi imsi;
        private final String apn;
        private final Instant start;
        private long ul;
        private long dl;
        private long grantedOctets;

        ChargingSession(ChargingId id, Imsi imsi, String apn, Product product, Instant start) {
            this.id = id;
            this.imsi = imsi;
            this.apn = apn;
            this.start = start;
        }

        synchronized void add(long u, long d) {
            ul += u;
            dl += d;
        }

        synchronized long totalOctets() {
            return ul + dl;
        }

        synchronized long grantedOctets() {
            return grantedOctets;
        }

        synchronized void setGrantedOctets(long grantedOctets) {
            this.grantedOctets = grantedOctets;
        }

        synchronized Cdr toCdr(Instant end, Cdr.Kind kind) {
            return new Cdr(id, imsi, apn, ul, dl, start, end, kind);
        }
    }
}
