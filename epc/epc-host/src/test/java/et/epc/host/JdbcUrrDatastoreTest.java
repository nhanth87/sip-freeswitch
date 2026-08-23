package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** SQL stays PostgreSQL; round-trip through the same thin-stub DataSource pattern as JdbcDurableSinkTest. */
class JdbcUrrDatastoreTest {

    @Test
    void sqlIsValidPostgresUpsertLoadDelete() {
        String[] sqls = {
            JdbcUrrDatastore.SQL_UPSERT,
            JdbcUrrDatastore.SQL_LOAD,
            JdbcUrrDatastore.SQL_DELETE
        };
        for (String sql : sqls) {
            assertFalse(sql.contains("`"), "no MySQL backticks");
            assertFalse(sql.contains("AUTOINCREMENT"), "no SQLite");
            assertFalse(sql.contains("AUTO_INCREMENT"), "no MySQL");
            assertFalse(sql.contains("MERGE INTO"), "no MERGE");
        }
        assertTrue(JdbcUrrDatastore.SQL_UPSERT.contains("INSERT INTO epc_urr_baseline"));
        assertTrue(JdbcUrrDatastore.SQL_UPSERT.contains("ON CONFLICT (urr_id) DO UPDATE"));
        assertTrue(JdbcUrrDatastore.SQL_UPSERT.contains("baseline_bytes = EXCLUDED.baseline_bytes"));
        assertTrue(JdbcUrrDatastore.SQL_LOAD.contains("SELECT baseline_bytes"));
        assertTrue(JdbcUrrDatastore.SQL_LOAD.contains("WHERE urr_id = ?"));
        assertTrue(JdbcUrrDatastore.SQL_DELETE.contains("DELETE FROM epc_urr_baseline WHERE urr_id = ?"));
    }

    @Test
    void migrationMatchesDatastoreContract() throws Exception {
        String v5 = new String(JdbcUrrDatastoreTest.class
                .getResourceAsStream("/db/migration/V5__epc_urr_baseline.sql").readAllBytes());
        assertTrue(v5.contains("CREATE TABLE IF NOT EXISTS epc_urr_baseline"));
        assertTrue(v5.contains("urr_id TEXT PRIMARY KEY"));
        assertTrue(v5.contains("baseline_bytes BIGINT NOT NULL DEFAULT 0"));
        assertTrue(v5.contains("updated_at TIMESTAMPTZ NOT NULL DEFAULT now()"));
    }

    @Test
    void roundTripsThroughThinStub() {
        Map<String, Long> rows = new HashMap<>();
        JdbcUrrDatastore store = new JdbcUrrDatastore(dataSource(rows));

        assertTrue(store.loadBaseline("urr-1").isEmpty());

        store.saveBaseline("urr-1", 1234L);
        assertEquals(Optional.of(1234L), store.loadBaseline("urr-1"));

        store.saveBaseline("urr-1", 5678L);
        assertEquals(Optional.of(5678L), store.loadBaseline("urr-1"), "upsert overwrites");

        store.saveBaseline("urr-2", 42L);
        store.delete("urr-1");
        assertTrue(store.loadBaseline("urr-1").isEmpty());
        assertEquals(Optional.of(42L), store.loadBaseline("urr-2"));
    }

    @Test
    void rejectsNegativeBaseline() {
        JdbcUrrDatastore store = new JdbcUrrDatastore(dataSource(new HashMap<>()));
        assertThrows(IllegalArgumentException.class, () -> store.saveBaseline("urr-1", -1L));
    }

    private static DataSource dataSource(Map<String, Long> rows) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcUrrDatastoreTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getConnection" -> connection(rows);
                    case "toString" -> "StubUrrDataSource";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Connection connection(Map<String, Long> rows) {
        return (Connection) Proxy.newProxyInstance(
                JdbcUrrDatastoreTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "prepareStatement" -> statement(rows, (String) args[0]);
                    case "close" -> null;
                    case "toString" -> "StubUrrConnection";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static PreparedStatement statement(Map<String, Long> rows, String sql) {
        Object[] params = new Object[3];
        return (PreparedStatement) Proxy.newProxyInstance(
                JdbcUrrDatastoreTest.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setString", "setLong" -> {
                        params[(Integer) args[0]] = args[1];
                        yield null;
                    }
                    case "executeUpdate" -> executeUpdate(rows, sql, params);
                    case "executeQuery" -> resultSet(rows, params);
                    case "close" -> null;
                    case "toString" -> "StubUrrStatement";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Integer executeUpdate(Map<String, Long> rows, String sql, Object[] params) {
        String urrId = (String) params[1];
        if (sql.contains("DELETE")) {
            rows.remove(urrId);
        } else {
            rows.put(urrId, (Long) params[2]);
        }
        return 1;
    }

    private static ResultSet resultSet(Map<String, Long> rows, Object[] params) {
        Long value = rows.get((String) params[1]);
        List<Long> out = value == null ? List.of() : List.of(value);
        int[] cursor = {-1};
        return (ResultSet) Proxy.newProxyInstance(
                JdbcUrrDatastoreTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        cursor[0]++;
                        yield cursor[0] < out.size();
                    }
                    case "getLong" -> out.get(cursor[0]);
                    case "close" -> null;
                    case "toString" -> "StubUrrResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> args != null && proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
