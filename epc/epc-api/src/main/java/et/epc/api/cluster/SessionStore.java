package et.epc.api.cluster;

import java.util.Optional;

public interface SessionStore {
    void put(SessionCheckpoint checkpoint);

    Optional<SessionCheckpoint> get(String sessionId);

    void remove(String sessionId);

    int size();

    /** SGW session by its S11-C TEID (header TEID of every later S11 request). */
    default Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        return Optional.empty();
    }

    /** PGW session by its S5-C TEID (header TEID of every later S5-C request). */
    default Optional<SessionCheckpoint> findByS5cTeid(int s5cTeid) {
        return Optional.empty();
    }
}
