package et.epc.pcrf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcPolicyRepositoryTest {

    @Test
    void sqlIsValidPostgresAndUsesKnownTables() {
        String sql = JdbcPolicyRepository.SQL_LOAD_RULES
                + JdbcPolicyRepository.SQL_UPSERT_SESSION
                + JdbcPolicyRepository.SQL_IPCAN_SESSION_IDS;
        assertFalse(sql.contains("`"), "no MySQL backticks");
        assertFalse(sql.contains("AUTOINCREMENT"), "no SQLite");
        assertFalse(sql.contains("AUTO_INCREMENT"), "no MySQL");
        assertFalse(sql.contains("MERGE INTO"), "no MERGE");

        assertTrue(JdbcPolicyRepository.SQL_LOAD_RULES.contains("FROM epc_pcc_rule"));
        assertTrue(JdbcPolicyRepository.SQL_LOAD_RULES.contains("WHERE apn = ?"));
        assertTrue(JdbcPolicyRepository.SQL_UPSERT_SESSION.contains("INSERT INTO epc_ipcan_session"));
        assertTrue(JdbcPolicyRepository.SQL_UPSERT_SESSION.contains("ON CONFLICT (session_id) DO UPDATE"));
        assertTrue(JdbcPolicyRepository.SQL_IPCAN_SESSION_IDS.contains("FROM epc_ipcan_session"));
        assertTrue(JdbcPolicyRepository.SQL_IPCAN_SESSION_IDS.contains("WHERE imsi = ? AND apn = ?"));
        assertTrue(JdbcPolicyRepository.SQL_IPCAN_SESSION_IDS.contains("SELECT session_id"));
        for (String col : new String[] {"name", "qci", "arp_priority", "preempt_cap", "preempt_vuln",
                "mbr_ul_bps", "mbr_dl_bps", "gbr_ul_bps", "gbr_dl_bps",
                "rating_group", "service_id", "flow_desc_json", "gating_open"}) {
            assertTrue(JdbcPolicyRepository.SQL_LOAD_RULES.contains(col), col);
        }
    }

    @Test
    void firstFlowParsesJsonArray() {
        assertEquals("permit out ip from any to any",
                JdbcPolicyRepository.firstFlow("[\"permit out ip from any to any\"]"));
        assertEquals("", JdbcPolicyRepository.firstFlow(null));
        assertEquals("", JdbcPolicyRepository.firstFlow(""));
        assertEquals("plain", JdbcPolicyRepository.firstFlow("plain"));
    }

    @Test
    void roundTripsLoadAndUpsertThroughStub() {
        State state = new State();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", "ims-signal-qci5");
        row.put("qci", 5);
        row.put("arp_priority", 1);
        row.put("preempt_cap", true);
        row.put("preempt_vuln", false);
        row.put("mbr_ul_bps", 1_000_000_000L);
        row.put("mbr_dl_bps", 1_000_000_000L);
        row.put("gbr_ul_bps", 0L);
        row.put("gbr_dl_bps", 0L);
        row.put("rating_group", 7);
        row.put("service_id", "ims-sip");
        row.put("flow_desc_json", "[\"permit out ip from any to any\"]");
        row.put("gating_open", true);
        state.ruleRows.put("ims", List.of(row));

        JdbcPolicyRepository repo = new JdbcPolicyRepository(dataSource(state));

        List<PccRule> rules = repo.loadRules("001010000000001", "ims");
        assertEquals(1, rules.size());
        PccRule r = rules.get(0);
        assertEquals("ims-signal-qci5", r.name());
        assertEquals(5, r.qci());
        assertEquals(7, r.ratingGroup());
        assertEquals("permit out ip from any to any", r.flowDescription());
        assertTrue(r.gatingOpen());

        repo.upsertIpCanSession("gx-1", "001010000000001", "ims", "10.65.0.2", 42L, "{}");
        assertEquals(1, state.upserts.size());
        assertEquals("gx-1", state.upserts.get(0)[1]);
        assertEquals(42L, state.upserts.get(0)[5]);
    }

    @Test
    void ipCanSessionIdsRoundTripsThroughStub() {
        State state = new State();
        state.ipcanRows.put("001010000000001/ims", List.of(
                Map.of("session_id", "gx-b"),
                Map.of("session_id", "gx-a")));
        JdbcPolicyRepository repo = new JdbcPolicyRepository(dataSource(state));

        List<String> ids = repo.ipCanSessionIds("001010000000001", "ims");
        assertEquals(List.of("gx-b", "gx-a"), ids);
        assertTrue(repo.ipCanSessionIds("001010000000001", "internet").isEmpty());
    }

    private static final class State {
        final List<String> sqls = new CopyOnWriteArrayList<>();
        final Map<String, List<Map<String, Object>>> ruleRows = new LinkedHashMap<>();
        final Map<String, List<Map<String, Object>>> ipcanRows = new LinkedHashMap<>();
        final List<Object[]> upserts = new CopyOnWriteArrayList<>();
    }

    private static DataSource dataSource(State state) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection(state);
                    case "toString" -> "StubDataSource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Connection connection(State state) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        String sql = (String) args[0];
                        state.sqls.add(sql);
                        yield preparedStatement(state, sql);
                    }
                    case "close" -> null;
                    case "toString" -> "StubConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement preparedStatement(State state, String sql) {
        Object[] params = new Object[8];
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setInt", "setLong", "setBoolean" -> {
                        params[(Integer) args[0]] = args[1];
                        yield null;
                    }
                    case "executeUpdate" -> {
                        state.upserts.add(params.clone());
                        yield 1;
                    }
                    case "executeQuery" -> resultSet(rowsFor(state, sql, params));
                    case "close" -> null;
                    case "toString" -> "StubPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static List<Map<String, Object>> rowsFor(State state, String sql, Object[] params) {
        if (sql.contains("epc_pcc_rule")) {
            String apn = params[1] == null ? "" : (String) params[1];
            return state.ruleRows.getOrDefault(apn, List.of());
        }
        if (sql.contains("epc_ipcan_session") && sql.contains("SELECT session_id")) {
            String key = params[1] + "/" + params[2];
            return state.ipcanRows.getOrDefault(key, List.of());
        }
        return List.of();
    }

    private static ResultSet resultSet(List<Map<String, Object>> rows) {
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        cursor[0]++;
                        yield cursor[0] < rows.size();
                    }
                    case "getString", "getInt", "getLong", "getBoolean" ->
                            rows.get(cursor[0]).get((String) args[0]);
                    case "wasNull" -> false;
                    case "close" -> null;
                    case "toString" -> "StubResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}