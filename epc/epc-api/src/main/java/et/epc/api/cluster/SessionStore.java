package et.epc.api.cluster;

import java.util.Optional;

public interface SessionStore {
    void put(SessionCheckpoint checkpoint);

    Optional<SessionCheckpoint> get(String sessionId);

    void remove(String sessionId);

    int size();

    default java.util.Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        return java.util.Optional.empty();
    }
}
