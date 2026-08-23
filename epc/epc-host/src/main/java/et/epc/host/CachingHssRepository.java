package et.epc.host;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.SubscriberProfile;
import et.epc.hss.HssRepository;
import et.epc.hss.SubscriberRecord;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.infinispan.Cache;

/**
 * Read-through Infinispan cache in front of the durable Postgres
 * {@link HssRepository}. Postgres stays the source of truth for every write and
 * for atomic {@code reserveSqn} ({@code UPDATE … RETURNING}); the cache only
 * accelerates profile/identity reads and is invalidated on mutation.
 *
 * <p>Phase D hardening:</p>
 * <ul>
 *   <li><b>IMPU→IMSI index</b> — Cx lookups by IMPU resolve through
 *   {@code impu:}-prefixed keys in the same {@code epc-subscribers} region
 *   ({@link ImpuIndex} values). IMSI snapshots are keyed by bare digits, so they
 *   can never collide with the prefixed index keys.</li>
 *   <li><b>Negative cache</b> — unknown-IMSI/unknown-IMPU lookups store a
 *   negative entry with a short TTL (default {@value #DEFAULT_NEGATIVE_TTL_MS} ms)
 *   so probe/scan traffic stops hammering Postgres.</li>
 *   <li><b>SQN and secrets are never cached</b> — {@link CachedSubscriber} has no
 *   SQN field (cached reads report a non-authoritative {@code sqn=0}); Ki/OPc/AMF
 *   live in {@code SecretStore}, never in this snapshot. {@code reserveSqn} /
 *   {@code forceSqn} always go to the durable row.</li>
 * </ul>
 *
 * <p>Invalidation on mutation covers the IMSI snapshot <em>and</em> the IMPU index
 * entries of the evicted snapshot. Mutations that bypass this layer (direct durable
 * writes) leave the cache stale until the region TTL — the same contract as pre-D.</p>
 */
public final class CachingHssRepository implements HssRepository {

    /**
     * Default negative-entry TTL (ms): long enough to absorb unknown-IMSI probe
     * scans, short enough that a freshly provisioned row surfaces quickly.
     */
    public static final long DEFAULT_NEGATIVE_TTL_MS = 30_000L;

    /** Key prefix of the IMPU→IMSI index entries inside the subscriber region. */
    static final String IMPU_INDEX_PREFIX = "impu:";

    /**
     * Point-in-time, non-secret subscriber snapshot (Serializable for ISPN).
     * SQN is deliberately absent: sequence truth stays on the durable row and
     * {@code reserveSqn}/{@code forceSqn} are never satisfied from this snapshot.
     */
    public record CachedSubscriber(
            SubscriberProfile profile,
            String servingMmeHost,
            boolean purged,
            String imsScscfUri,
            String impi,
            List<String> impus,
            String serviceProfileJson,
            boolean negative) implements Serializable {

        /** Negative marker for an unknown IMSI; stored with the short negative TTL. */
        public static final CachedSubscriber NEGATIVE =
                new CachedSubscriber(null, null, false, null, null, List.of(), null, true);

        public CachedSubscriber {
            impus = impus == null ? List.of() : List.copyOf(impus);
        }

        SubscriberRecord toRecord() {
            // sqn=0 by design: SQN is never served from cache. Callers needing
            // sequence truth use reserveSqn/forceSqn against the durable store.
            SubscriberRecord rec = new SubscriberRecord(profile, 0L);
            rec.setServingMmeHost(servingMmeHost);
            rec.setPurged(purged);
            rec.setImsScscfUri(imsScscfUri);
            rec.setImpi(impi);
            rec.setImpus(impus);
            rec.setServiceProfileJson(serviceProfileJson);
            return rec;
        }
    }

    /** IMPU→IMSI index entry; {@link #NEGATIVE} marks an unknown IMPU (short TTL). */
    public record ImpuIndex(String imsi, boolean negative) implements Serializable {

        /** Negative marker for an unknown IMPU; stored with the short negative TTL. */
        public static final ImpuIndex NEGATIVE = new ImpuIndex(null, true);

        static ImpuIndex of(String imsi) {
            return new ImpuIndex(imsi, false);
        }
    }

    private final HssRepository delegate;
    private final Cache<String, CachedSubscriber> cache;
    /** Same ISPN region viewed for {@code impu:}-prefixed index entries. */
    private final Cache<String, Object> impuIndex;
    private final long negativeTtlMs;

    public CachingHssRepository(HssRepository delegate, Cache<String, CachedSubscriber> cache) {
        this(delegate, cache, DEFAULT_NEGATIVE_TTL_MS);
    }

    /** Test/override seam: custom negative-entry TTL (milliseconds, positive). */
    @SuppressWarnings("unchecked")
    public CachingHssRepository(HssRepository delegate, Cache<String, CachedSubscriber> cache,
                                long negativeTtlMs) {
        this.delegate = Objects.requireNonNull(delegate);
        this.cache = Objects.requireNonNull(cache);
        this.impuIndex = (Cache<String, Object>) (Cache<?, ?>) cache;
        if (negativeTtlMs <= 0) {
            throw new IllegalArgumentException("negativeTtlMs must be positive");
        }
        this.negativeTtlMs = negativeTtlMs;
    }

    @Override
    public void save(SubscriberRecord rec) {
        delegate.save(rec);
        invalidate(rec.imsi());
    }

    @Override
    public Optional<SubscriberRecord> find(Imsi imsi) {
        CachedSubscriber cached = cache.get(imsi.digits());
        if (cached != null) {
            return cached.negative() ? Optional.empty() : Optional.of(cached.toRecord());
        }
        Optional<SubscriberRecord> rec = delegate.find(imsi);
        if (rec.isPresent()) {
            cache(rec.get());
        } else {
            // Negative entry (short TTL): stop repeated unknown-IMSI lookups from
            // hammering Postgres.
            cache.put(imsi.digits(), CachedSubscriber.NEGATIVE, negativeTtlMs, TimeUnit.MILLISECONDS);
        }
        return rec;
    }

    @Override
    public long reserveSqn(Imsi imsi) {
        // Atomic on the durable row; never satisfied from the cache.
        long next = delegate.reserveSqn(imsi);
        invalidate(imsi);
        return next;
    }

    @Override
    public void forceSqn(Imsi imsi, long next) {
        delegate.forceSqn(imsi, next);
        invalidate(imsi);
    }

    @Override
    public void setServingMme(Imsi imsi, String host) {
        delegate.setServingMme(imsi, host);
        invalidate(imsi);
    }

    @Override
    public void setPurged(Imsi imsi, boolean purged) {
        delegate.setPurged(imsi, purged);
        invalidate(imsi);
    }

    @Override
    public void setImsScscf(Imsi imsi, String uri) {
        delegate.setImsScscf(imsi, uri);
        invalidate(imsi);
    }

    @Override
    public Optional<SubscriberRecord> findByImpi(String impi) {
        return delegate.findByImpi(impi).map(this::cacheAndReturn);
    }

    @Override
    public Optional<SubscriberRecord> findByImpu(String impu) {
        String key = impuKey(impu);
        Object idx = impuIndex.get(key);
        if (idx instanceof ImpuIndex ix) {
            if (ix.negative()) {
                return Optional.empty();
            }
            Optional<SubscriberRecord> viaImsi = find(new Imsi(ix.imsi()));
            if (viaImsi.isPresent()) {
                return viaImsi;
            }
            // Stale index (durable row gone): drop it and fall back to the delegate.
            impuIndex.remove(key);
        }
        Optional<SubscriberRecord> rec = delegate.findByImpu(impu);
        if (rec.isPresent()) {
            cache(rec.get());
            indexImpus(rec.get());
        } else {
            // Negative index entry (short TTL) for an unknown IMPU.
            impuIndex.put(key, ImpuIndex.NEGATIVE, negativeTtlMs, TimeUnit.MILLISECONDS);
        }
        return rec;
    }

    @Override
    public void saveImsIdentity(Imsi imsi, String impi, List<String> impus, String serviceProfileJson) {
        delegate.saveImsIdentity(imsi, impi, impus, serviceProfileJson);
        invalidate(imsi);
        // Index the freshly persisted IMPUs so the very next Cx lookup is cache-served.
        if (impus != null) {
            for (String impu : impus) {
                if (impu != null && !impu.isBlank()) {
                    impuIndex.put(impuKey(impu), ImpuIndex.of(imsi.digits()));
                }
            }
        }
    }

    @Override
    public void upsertApnProfile(Imsi imsi, ApnConfig apn, int chargingCharacteristics, boolean defaultApn) {
        delegate.upsertApnProfile(imsi, apn, chargingCharacteristics, defaultApn);
        invalidate(imsi);
    }

    @Override
    public void deleteApnProfile(Imsi imsi, String apn) {
        delegate.deleteApnProfile(imsi, apn);
        invalidate(imsi);
    }

    @Override
    public void setImpuRegState(Imsi imsi, String state, String scscfUri) {
        // Durable write-through; the cached snapshot is dropped so the next read sees the new state.
        delegate.setImpuRegState(imsi, state, scscfUri);
        invalidate(imsi);
    }

    @Override
    public String locateScscf(Imsi imsi) {
        // Registration state is never served from cache — Postgres stays the source of truth.
        return delegate.locateScscf(imsi);
    }

    @Override
    public int size() {
        return delegate.size();
    }

    @Override
    public List<SubscriberRecord> findAll() {
        return delegate.findAll();
    }

    /**
     * Boot-time identity warm: bulk-load every durable subscriber into the ISPN cache so the
     * S6a/Cx hot paths read identity from cache, not a per-request Postgres {@code find()}.
     * IMPU index entries are warmed alongside so Cx lookups by IMPU are cache-served from boot.
     */
    public void warm() {
        for (SubscriberRecord rec : delegate.findAll()) {
            cache(rec);
            indexImpus(rec);
        }
    }

    private SubscriberRecord cacheAndReturn(SubscriberRecord rec) {
        cache(rec);
        return rec;
    }

    private void cache(SubscriberRecord rec) {
        cache.put(rec.imsi().digits(), snapshot(rec));
    }

    /** Index every IMPU of the record onto the IMPU→IMSI index (same region, prefixed keys). */
    private void indexImpus(SubscriberRecord rec) {
        String imsi = rec.imsi().digits();
        for (String impu : rec.impus()) {
            if (impu != null && !impu.isBlank()) {
                impuIndex.put(impuKey(impu), ImpuIndex.of(imsi));
            }
        }
    }

    private void invalidate(Imsi imsi) {
        CachedSubscriber old = cache.get(imsi.digits());
        cache.remove(imsi.digits());
        // Cover the index entries of the evicted snapshot (also clears NEGATIVE).
        if (old != null && !old.negative()) {
            for (String impu : old.impus()) {
                if (impu != null && !impu.isBlank()) {
                    impuIndex.remove(impuKey(impu));
                }
            }
        }
    }

    /**
     * Normalized IMPU index key: trim, strip a leading {@code sip:} (case-insensitive,
     * matching the durable lookup semantics), lowercase, then prefix. Store and
     * lookup both normalize, so the index is consistent regardless of input form.
     */
    static String impuKey(String impu) {
        String s = impu == null ? "" : impu.trim();
        if (s.length() > 4 && s.regionMatches(true, 0, "sip:", 0, 4)) {
            s = s.substring(4);
        }
        return IMPU_INDEX_PREFIX + s.toLowerCase(Locale.ROOT);
    }

    private static CachedSubscriber snapshot(SubscriberRecord rec) {
        // No SQN, no secrets: identity/profile columns only (see class javadoc).
        return new CachedSubscriber(
                rec.profile(),
                rec.servingMmeHost(),
                rec.purged(),
                rec.imsScscfUri(),
                rec.impi(),
                rec.impus(),
                rec.serviceProfileJson(),
                false);
    }
}