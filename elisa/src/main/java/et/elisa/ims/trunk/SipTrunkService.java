package et.elisa.ims.trunk;

import et.elisa.persist.SipTrunkEntity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * SIP trunk registry (USSDGW-inspired). Postgres SoT so any S-CSCF node resolves
 * the same ISC/MRF URI. Falls back to {@code sip.trunk.freeswitch-uri} when table empty.
 */
@ApplicationScoped
public class SipTrunkService {

    private static final Logger LOG = LogManager.getLogger(SipTrunkService.class);

    @ConfigProperty(name = "sip.trunk.freeswitch-uri", defaultValue = "sip:1000@127.0.0.1:5062")
    String fallbackTrunkUri;

    /** Unit-test / lab when Panache is unavailable. */
    private final CopyOnWriteArrayList<SipTrunkEntity> memory = new CopyOnWriteArrayList<>();
    private volatile boolean memoryOnly;

    public SipTrunkService() {}

    /** Test ctor — memory-only rows + props fallback. */
    public SipTrunkService memoryOnly(String fallbackUri) {
        this.memoryOnly = true;
        this.fallbackTrunkUri = fallbackUri == null ? "sip:1000@127.0.0.1:5062" : fallbackUri;
        return this;
    }

    @Transactional
    public List<SipTrunkEntity> listAll() {
        if (memoryOnly) {
            return List.copyOf(memory);
        }
        return SipTrunkEntity.find("order by kind, priority, id").list();
    }

    @Transactional
    public List<SipTrunkEntity> listEnabled() {
        if (memoryOnly) {
            List<SipTrunkEntity> out = new ArrayList<>();
            for (SipTrunkEntity t : memory) {
                if (t.enabled) {
                    out.add(t);
                }
            }
            out.sort(Comparator.comparingInt((SipTrunkEntity t) -> t.priority)
                    .thenComparing(t -> t.id == null ? "" : t.id));
            return out;
        }
        return SipTrunkEntity.find("enabled = true order by priority, id").list();
    }

    @Transactional
    public Optional<SipTrunkEntity> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        if (memoryOnly) {
            return memory.stream().filter(t -> id.equals(t.id)).findFirst();
        }
        return SipTrunkEntity.findByIdOptional(id.trim());
    }

    /**
     * Primary enabled trunk for kind (ISC or MRF), lowest priority wins.
     * Blank tenant matches shared trunks; tenant-owned preferred when tenantId set.
     */
    @Transactional
    public Optional<SipTrunkEntity> pickPrimary(String kind) {
        return pickPrimary(kind, null);
    }

    @Transactional
    public Optional<SipTrunkEntity> pickPrimary(String kind, String tenantId) {
        String k = normalizeKind(kind);
        List<SipTrunkEntity> enabled = listEnabled();
        SipTrunkEntity bestShared = null;
        SipTrunkEntity bestTenant = null;
        for (SipTrunkEntity t : enabled) {
            if (!k.equalsIgnoreCase(t.kind)) {
                continue;
            }
            if (tenantId != null && !tenantId.isBlank()
                    && t.tenantId != null && tenantId.trim().equals(t.tenantId.trim())) {
                if (bestTenant == null || t.priority < bestTenant.priority) {
                    bestTenant = t;
                }
            } else if (t.tenantId == null || t.tenantId.isBlank()) {
                if (bestShared == null || t.priority < bestShared.priority) {
                    bestShared = t;
                }
            }
        }
        if (bestTenant != null) {
            return Optional.of(bestTenant);
        }
        return Optional.ofNullable(bestShared);
    }

    /**
     * Resolve ISC AS / MRF URI: DB primary (ISC then MRF) → props fallback.
     * Used by {@link et.elisa.ims.scscf.IscAsRouter} and ProxySbb bootstrap.
     */
    @Transactional
    public String resolveIscOrMrfUri() {
        Optional<SipTrunkEntity> isc = pickPrimary(SipTrunkEntity.KIND_ISC);
        if (isc.isPresent() && isc.get().sipUri != null && !isc.get().sipUri.isBlank()) {
            return isc.get().sipUri.trim();
        }
        Optional<SipTrunkEntity> mrf = pickPrimary(SipTrunkEntity.KIND_MRF);
        if (mrf.isPresent() && mrf.get().sipUri != null && !mrf.get().sipUri.isBlank()) {
            return mrf.get().sipUri.trim();
        }
        return fallbackTrunkUri;
    }

    public String fallbackUri() {
        return fallbackTrunkUri;
    }

    @Transactional
    public SipTrunkEntity upsert(String id, String name, String peerHost, int peerPort,
                                 String transport, String sipUri, boolean enabled,
                                 String tenantId, String kind, int priority) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id required");
        }
        if (peerHost == null || peerHost.isBlank()) {
            throw new IllegalArgumentException("peerHost required");
        }
        if (sipUri == null || sipUri.isBlank()) {
            throw new IllegalArgumentException("sipUri required");
        }
        String tid = id.trim();
        SipTrunkEntity e;
        if (memoryOnly) {
            e = byId(tid).orElseGet(SipTrunkEntity::new);
            if (e.id == null) {
                memory.add(e);
            }
        } else {
            e = SipTrunkEntity.findById(tid);
            if (e == null) {
                e = new SipTrunkEntity();
                e.id = tid;
                e.createdAt = Instant.now();
            }
        }
        e.id = tid;
        e.name = blankTo(name, tid);
        e.peerHost = peerHost.trim();
        e.peerPort = peerPort <= 0 ? 5060 : peerPort;
        e.transport = normalizeTransport(transport);
        e.sipUri = sipUri.trim();
        e.enabled = enabled;
        e.tenantId = blank(tenantId);
        e.kind = normalizeKind(kind);
        e.priority = priority <= 0 ? 100 : priority;
        e.updatedAt = Instant.now();
        if (!memoryOnly) {
            e.persist();
        }
        LOG.info("SIP trunk upsert id={} kind={} peer={}:{} uri={}",
                tid, e.kind, e.peerHost, e.peerPort, e.sipUri);
        return e;
    }

    @Transactional
    public boolean delete(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (memoryOnly) {
            return memory.removeIf(t -> id.equals(t.id));
        }
        return SipTrunkEntity.deleteById(id.trim());
    }

    /** Seed default FS trunk when table empty (first-run safety beyond Flyway seed). */
    @Transactional
    public void ensureDefaultIfEmpty() {
        if (memoryOnly) {
            if (memory.isEmpty()) {
                upsert("fs-default", "FreeSWITCH lab ISC/MRF", "127.0.0.1", 5062,
                        "UDP", fallbackTrunkUri, true, null, SipTrunkEntity.KIND_ISC, 10);
            }
            return;
        }
        long count = SipTrunkEntity.count();
        if (count == 0) {
            upsert("fs-default", "FreeSWITCH lab ISC/MRF", "127.0.0.1", 5062,
                    "UDP", fallbackTrunkUri, true, null, SipTrunkEntity.KIND_ISC, 10);
            LOG.info("Seeded default sf_sip_trunk from sip.trunk.freeswitch-uri");
        }
    }

    static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return SipTrunkEntity.KIND_ISC;
        }
        String u = kind.trim().toUpperCase(Locale.ROOT);
        return switch (u) {
            case SipTrunkEntity.KIND_MRF, SipTrunkEntity.KIND_PSTN, SipTrunkEntity.KIND_ISC -> u;
            default -> SipTrunkEntity.KIND_ISC;
        };
    }

    private static String normalizeTransport(String t) {
        if (t == null || t.isBlank()) {
            return "UDP";
        }
        String u = t.trim().toUpperCase(Locale.ROOT);
        return "TCP".equals(u) || "TLS".equals(u) ? u : "UDP";
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String blankTo(String s, String def) {
        return s == null || s.isBlank() ? def : s.trim();
    }
}
