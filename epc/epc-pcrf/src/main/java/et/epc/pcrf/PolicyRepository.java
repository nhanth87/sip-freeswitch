package et.epc.pcrf;

import java.util.List;

/**
 * Durable PCRF SoT. Production is Postgres {@link JdbcPolicyRepository}; tests
 * use in-heap doubles. Never call synchronously from the GTP receive loop — the
 * cache in front of this interface is the hot-path guard.
 */
public interface PolicyRepository {

    /**
     * Rule set for the IP-CAN session keyed by {@code (imsi, apn)}. Rules are
     * APN-scoped in the pilot; IMSI is the tenant disambiguator on lookup but
     * the row set is defined per APN.
     */
    List<PccRule> loadRules(String imsi, String apn);

    /** Persist (upsert) the IP-CAN session decision snapshot. */
    void upsertIpCanSession(String sessionId, String imsi, String apn,
                            String ueIpv4, long chargingId, String decisionJson);

    /**
     * Active Gx Session-Ids ({@code epc_ipcan_session}) for {@code (imsi, apn)},
     * newest first. Empty when the implementation keeps no session table —
     * heap test doubles inherit this default.
     */
    default List<String> ipCanSessionIds(String imsi, String apn) {
        return List.of();
    }

    /** Admin write: create or replace the PCC rule {@code (apn, rule.name)}. */
    default void upsertRule(String apn, PccRule rule) {
        throw new UnsupportedOperationException("read-only policy repository");
    }

    /** IMSIs holding an IP-CAN session on {@code apn} — RAR fan-out after a rule change. */
    default List<String> imsisWithSessions(String apn) {
        return List.of();
    }
}