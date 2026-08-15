package et.epc.pcef;

import et.epc.api.Imsi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PCEF: static Gx first, Gy/Gz sessions correlated by charging-id.
 * Fail-closed for prepaid when OCS is required; fail-open for postpaid offline.
 */
public final class PcefService {

    public enum Product { POSTPAID, PREPAID }

    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, ChargingSession> sessions = new ConcurrentHashMap<>();
    private final List<Cdr> cdrs = new ArrayList<>();
    private volatile boolean ocsLive;
    private volatile boolean pcrfLive;

    public ChargingId open(Imsi imsi, String apn, Product product) {
        Objects.requireNonNull(imsi);
        if (product == Product.PREPAID && !ocsLive) {
            throw new IllegalStateException("Gy OCS not live — fail-closed for prepaid");
        }
        ChargingId id = new ChargingId(ids.getAndIncrement());
        sessions.put(id.value(), new ChargingSession(id, imsi, apn, product, Instant.now()));
        return id;
    }

    public PolicyDecision authorize(String apn) {
        if ("ims".equalsIgnoreCase(apn)) {
            if (!pcrfLive) {
                return PolicyDecision.ims(); // static until Gx PCRF is live — not VoLTE QoS
            }
            return PolicyDecision.ims();
        }
        return PolicyDecision.staticDefault();
    }

    public void reportUsage(UsageRecord usage) {
        ChargingSession s = sessions.get(usage.chargingId().value());
        if (s == null) {
            throw new IllegalStateException("no charging session " + usage.chargingId().value());
        }
        s.add(usage.uplinkOctets(), usage.downlinkOctets());
    }

    public Cdr close(ChargingId id) {
        ChargingSession s = sessions.remove(id.value());
        if (s == null) {
            throw new IllegalStateException("no charging session");
        }
        Cdr cdr = s.toCdr(Instant.now());
        synchronized (cdrs) {
            cdrs.add(cdr);
        }
        return cdr;
    }

    public boolean reconcile(UsageRecord up, Cdr cdr) {
        return up.chargingId().equals(cdr.chargingId())
                && up.uplinkOctets() == cdr.uplinkOctets()
                && up.downlinkOctets() == cdr.downlinkOctets();
    }

    public void setOcsLive(boolean live) {
        this.ocsLive = live;
    }

    public void setPcrfLive(boolean live) {
        this.pcrfLive = live;
    }

    public boolean ocsLive() {
        return ocsLive;
    }

    public boolean pcrfLive() {
        return pcrfLive;
    }

    public List<Cdr> cdrs() {
        synchronized (cdrs) {
            return List.copyOf(cdrs);
        }
    }

    private static final class ChargingSession {
        private final ChargingId id;
        private final Imsi imsi;
        private final String apn;
        private final Instant start;
        private long ul;
        private long dl;

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

        synchronized Cdr toCdr(Instant end) {
            return new Cdr(id, imsi, apn, ul, dl, start, end);
        }
    }
}
