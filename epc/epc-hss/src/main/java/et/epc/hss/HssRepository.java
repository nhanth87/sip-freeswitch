package et.epc.hss;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import java.util.List;
import java.util.Optional;

/**
 * Subscriber + SQN store. Production is Postgres ({@link JdbcHssRepository});
 * heap is tests/lab only. SQN reservation must be atomic on the durable row.
 */
public interface HssRepository {

    void save(SubscriberRecord rec);

    Optional<SubscriberRecord> find(Imsi imsi);

    /** Atomically increment SQN and return the value used in AUTN. */
    long reserveSqn(Imsi imsi);

    void forceSqn(Imsi imsi, long next);

    void setServingMme(Imsi imsi, String host);

    void setPurged(Imsi imsi, boolean purged);

    void setImsScscf(Imsi imsi, String uri);

    /** IMS identity lookup for Cx. Identity is normalized by the implementation. */
    Optional<SubscriberRecord> findByImpi(String impi);

    Optional<SubscriberRecord> findByImpu(String impu);

    /** Persist IMPI / IMPU list / iFC JSON on the subscriber row (no secrets). */
    void saveImsIdentity(Imsi imsi, String impi, List<String> impus, String serviceProfileJson);

    /**
     * Upsert one relational APN configuration profile row ({@code epc_apn_profile},
     * TS 23.008 §2.3). When an IMSI has rows, reads prefer them over the legacy
     * {@code apns_csv} profile on the subscriber row. Cache invalidation rides the
     * decorating repository's mutation path — Postgres stays the source of truth.
     */
    void upsertApnProfile(Imsi imsi, ApnConfig apn, int chargingCharacteristics, boolean defaultApn);

    /** Delete one APN configuration profile row; no-op when absent. */
    void deleteApnProfile(Imsi imsi, String apn);

    /**
     * Persist the IMPU registration state (TS 23.002 / TS 29.228 SAR state machine):
     * {@code NOT_REGISTERED} / {@code REGISTERED} / {@code UNREGISTERED}, together with
     * the S-CSCF URI stored alongside it ({@code null} clears the stored server, e.g.
     * deregistration / RTR). Durable write only — never derived from a cache entry.
     */
    void setImpuRegState(Imsi imsi, String state, String scscfUri);

    /**
     * Locate the stored S-CSCF URI for this subscriber, registration-state aware:
     * the S-CSCF set by SAR (or the legacy assignment column) when present, {@code null}
     * when the IMPU is not registered / no server stored.
     */
    String locateScscf(Imsi imsi);

    /** All durable subscriber rows — used only for a boot-time cache warm, never per request. */
    List<SubscriberRecord> findAll();

    int size();
}
