package et.epc.api.cluster;

import java.util.List;
import java.util.Optional;

/** Off-hot-path durable checkpoint (Postgres). GTP receive loops must not call this synchronously. */
public interface DurableSink {
    void checkpoint(SessionCheckpoint session);

    void forget(String sessionId);

    Optional<SessionCheckpoint> load(String sessionId);

    List<SessionCheckpoint> loadByOwner(String nodeId);

    /**
     * Postgres (or LOCAL heap sink) lookup by S11-C TEID. Default empty —
     * GTP RAB must not assume a SQL engine other than PostgreSQL.
     */
    default Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        return Optional.empty();
    }
}
