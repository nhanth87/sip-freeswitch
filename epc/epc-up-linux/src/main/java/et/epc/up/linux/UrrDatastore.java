package et.epc.up.linux;

import java.util.Optional;

/** Off-hot-path URR baseline persistence. The PostgreSQL impl lives in the epc-host sibling. */
public interface UrrDatastore {

    void saveBaseline(String urrId, long baselineBytes);

    Optional<Long> loadBaseline(String urrId);

    void delete(String urrId);
}