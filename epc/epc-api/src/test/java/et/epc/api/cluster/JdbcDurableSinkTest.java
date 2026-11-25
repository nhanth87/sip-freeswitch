package et.epc.api.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcDurableSinkTest {

    private static final String[] COLS = {
        "session_id", "role", "imsi", "apn", "ebi", "owner_node_id", "generation",
        "ue_ipv4", "sgw_s1u_key", "sgw_s5u_key", "pgw_s5u_key", "charging_id",
        "pfcp_seid_hex", "s11c_teid", "pfcp_seid",
        "mme_s11_key", "sgw_s5c_key", "pgw_s5c_key", "pgwc_peer", "s5c_teid"
    };

    private static SessionCheckpoint cp(String id, String role, String owner, int s11cTeid) {
        return new SessionCheckpoint(id, role, "001010000000001", "internet", 5,
                owner, 3L, "10.64.0.2", null, null, null, 42L, "00ff", s11cTeid, 55L);
    }

    @Test
    void sqlIsValidPostgresAndUsesKnownColumns() {
        String[] sqls = {
            JdbcDurableSink.SQL_UPSERT,
            JdbcDurableSink.SQL_DELETE,
            JdbcDurableSink.SQL_LOAD,
            JdbcDurableSink.SQL_LOAD_BY_OWNER,
            JdbcDurableSink.SQL_FIND_BY_S11C_TEID
        };
        for (String sql : sqls) {
            assertFalse(sql.contains("`"), "no MySQL backticks");
            assertFalse(sql.contains("AUTOINCREMENT"), "no SQLite");
            assertFalse(sql.contains("AUTO_INCREMENT"), "no MySQL");
            assertFalse(sql.contains("IDENTITY(1,1)"), "no SQL Server");
            assertFalse(sql.contains("MERGE INTO"), "no MERGE");
        }
        assertTrue(JdbcDurableSink.SQL_UPSERT.contains("INSERT INTO epc_session_checkpoint"));
        assertTrue(JdbcDurableSink.SQL_UPSERT.contains("ON CONFLICT (session_id) DO UPDATE"));
        assertTrue(JdbcDurableSink.SQL_DELETE.contains("DELETE FROM epc_session_checkpoint WHERE session_id = ?"));
        assertTrue(JdbcDurableSink.SQL_LOAD.contains("WHERE session_id = ?"));
        assertTrue(JdbcDurableSink.SQL_LOAD_BY_OWNER.contains("WHERE owner_node_id = ?"));
        assertTrue(JdbcDurableSink.SQL_FIND_BY_S11C_TEID.contains("WHERE s11c_teid = ? AND role = 'sgw'"));
        assertTrue(JdbcDurableSink.SQL_FIND_BY_S11C_TEID.contains("LIMIT 1"));

        for (String col : COLS) {
            assertTrue(JdbcDurableSink.SQL_UPSERT.contains(col), col);
            assertTrue(JdbcDurableSink.SQL_LOAD.contains(col), col);
        }
    }

    @Test
    void roundTripsThroughThinStub() {
        State state = new State();
        JdbcDurableSink sink = new JdbcDurableSink(dataSource(state));

        SessionCheckpoint a = cp("sgw|1", "sgw", "n1", 0x11);
        SessionCheckpoint b = cp("sgw|2", "sgw", "n1", 0x22);
        SessionCheckpoint c = cp("pgw|3", "pgw", "n2", 0x33);

        sink.checkpoint(a);
        sink.checkpoint(b);
        sink.checkpoint(c);

        assertEquals(a, sink.load("sgw|1").orElseThrow());
        assertTrue(sink.load("sgw|missing").isEmpty());

        assertEquals(2, sink.loadByOwner("n1").size());
        assertTrue(sink.loadByOwner("n9").isEmpty());

        assertEquals(0x22, sink.findByS11cTeid(0x22).orElseThrow().s11cTeid());
        assertTrue(sink.findByS11cTeid(0x33).isEmpty()); // pgw rows excluded
        assertTrue(sink.findByS11cTeid(0x99).isEmpty());

        sink.forget("sgw|1");
        assertTrue(sink.load("sgw|1").isEmpty());
    }

    /** W-5: the GTP-C peers survive in Postgres, so another node answers with the right TEIDs. */
    @Test
    void controlPeersRoundTripAndPgwIsFoundByItsS5cTeid() throws Exception {
        State state = new State();
        JdbcDurableSink sink = new JdbcDurableSink(dataSource(state));
        java.net.InetAddress mme = java.net.InetAddress.getByName("192.0.2.1");
        java.net.InetAddress sgw = java.net.InetAddress.getByName("192.0.2.14");
        SessionCheckpoint s = cp("sgw|9", "sgw", "n1", 0x0101_0009).withControlPeers(
                new et.epc.api.Fteid(0xa001, mme, 10), new et.epc.api.Fteid(0x0101_0010, sgw, 6),
                new et.epc.api.Fteid(0x0202_0001, java.net.InetAddress.getByName("192.0.2.13"), 7),
                "192.0.2.13:2123", 0);
        SessionCheckpoint p = cp("pgw|9", "pgw", "n2", 0).withControlPeers(
                null, new et.epc.api.Fteid(0x0101_0010, sgw, 6), null, null, 0x0202_0001);
        sink.checkpoint(s);
        sink.checkpoint(p);
        SessionCheckpoint back = sink.findByS11cTeid(0x0101_0009).orElseThrow();
        assertEquals(0xa001, back.mmeS11().teid());
        assertEquals(10, back.mmeS11().interfaceType());
        assertEquals("192.0.2.13:2123", back.pgwcPeer());
        assertEquals(0x0101_0010, sink.findByS5cTeid(0x0202_0001).orElseThrow().sgwS5c().teid());
        assertTrue(sink.findByS5cTeid(0x0101_0009).isEmpty());
    }

    private static final class State {
        final List<String> sqls = new CopyOnWriteArrayList<>();
        final Map<String, SessionCheckpoint> rows = new LinkedHashMap<>();
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
        Object[] params = new Object[21];
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setInt", "setLong" -> {
                        params[(Integer) args[0]] = args[1];
                        yield null;
                    }
                    case "executeUpdate" -> executeUpdate(state, sql, params);
                    case "executeQuery" -> resultSet(rowsFor(state, sql, params));
                    case "close" -> null;
                    case "toString" -> "StubPreparedStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Integer executeUpdate(State state, String sql, Object[] p) {
        if (sql.contains("DELETE")) {
            state.rows.remove((String) p[1]);
            return 1;
        }
        SessionCheckpoint cp = row(p);
        state.rows.put(cp.sessionId(), cp);
        return 1;
    }

    private static List<Map<String, Object>> rowsFor(State state, String sql, Object[] p) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (sql.contains("WHERE owner_node_id")) {
            String owner = (String) p[1];
            for (SessionCheckpoint cp : state.rows.values()) {
                if (cp.ownerNodeId().equals(owner)) {
                    out.add(rowMap(cp));
                }
            }
        } else if (sql.contains("WHERE s5c_teid")) {
            int teid = (Integer) p[1];
            for (SessionCheckpoint cp : state.rows.values()) {
                if ("pgw".equals(cp.role()) && cp.s5cTeid() == teid) {
                    out.add(rowMap(cp));
                    break;
                }
            }
        } else if (sql.contains("WHERE s11c_teid")) {
            int teid = (Integer) p[1];
            for (SessionCheckpoint cp : state.rows.values()) {
                if ("sgw".equals(cp.role()) && cp.s11cTeid() == teid) {
                    out.add(rowMap(cp));
                    break;
                }
            }
        } else {
            SessionCheckpoint cp = state.rows.get((String) p[1]);
            if (cp != null) {
                out.add(rowMap(cp));
            }
        }
        return out;
    }

    private static SessionCheckpoint row(Object[] p) {
        return new SessionCheckpoint(
                (String) p[1], (String) p[2], (String) p[3], (String) p[4], (Integer) p[5],
                (String) p[6], (Long) p[7], (String) p[8], (String) p[9], (String) p[10],
                (String) p[11], (Long) p[12], (String) p[13], (Integer) p[14], (Long) p[15],
                (String) p[16], (String) p[17], (String) p[18], (String) p[19], (Integer) p[20]);
    }

    private static Map<String, Object> rowMap(SessionCheckpoint cp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_id", cp.sessionId());
        m.put("role", cp.role());
        m.put("imsi", cp.imsiDigits());
        m.put("apn", cp.apn());
        m.put("ebi", cp.ebi());
        m.put("owner_node_id", cp.ownerNodeId());
        m.put("generation", cp.generation());
        m.put("ue_ipv4", cp.ueIpv4());
        m.put("sgw_s1u_key", cp.sgwS1uKey());
        m.put("sgw_s5u_key", cp.sgwS5uKey());
        m.put("pgw_s5u_key", cp.pgwS5uKey());
        m.put("charging_id", cp.chargingId());
        m.put("pfcp_seid_hex", cp.pfcpSeidHex());
        m.put("s11c_teid", cp.s11cTeid());
        m.put("pfcp_seid", cp.pfcpSeid());
        m.put("mme_s11_key", cp.mmeS11Key());
        m.put("sgw_s5c_key", cp.sgwS5cKey());
        m.put("pgw_s5c_key", cp.pgwS5cKey());
        m.put("pgwc_peer", cp.pgwcPeer());
        m.put("s5c_teid", cp.s5cTeid());
        return m;
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
                    case "getString", "getInt", "getLong" ->
                            rows.get(cursor[0]).get((String) args[0]);
                    case "close" -> null;
                    case "toString" -> "StubResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}