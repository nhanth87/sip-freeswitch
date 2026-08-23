package et.epc.pcrf;

import java.util.List;

/**
 * Read-through cache for policy decisions. Keyed by {@code (imsi, apn)}. The
 * heap implementation backs onto {@link PolicyRepository}; a host-provided
 * Infinispan implementation (cache name {@code epc-cluster}) may be injected by
 * the lead in {@code EpcBootstrap} — decisions are non-secret only.
 */
public interface PccCache {

    List<PccRule> rulesFor(String imsi, String apn);

    void putRules(String imsi, String apn, List<PccRule> rules);

    void invalidate(String imsi, String apn);

    static String key(String imsi, String apn) {
        return imsi + "/" + apn;
    }
}