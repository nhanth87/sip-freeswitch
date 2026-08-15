package et.epc.hss;

import et.epc.api.Imsi;
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

    int size();
}
