package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class EpcJdbcTest {

    @Test
    void packagedUrlIsDedicatedPostgresEpc() throws Exception {
        Properties props = new Properties();
        try (InputStream in = EpcRuntime.class.getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        String raw = props.getProperty("epc.jdbc.url");
        assertTrue(raw.contains("jdbc:postgresql://postgres-epc:5432/epc"), raw);
        String lower = raw.toLowerCase();
        assertTrue(!lower.contains("h2") && !lower.contains("derby") && !lower.contains("sqlite"), raw);
        EpcJdbc.requirePostgres(EpcJdbc.DEFAULT_URL);
    }

    @Test
    void rejectsH2DerbySqliteAndElisaDb() {
        assertThrows(IllegalStateException.class, () -> EpcJdbc.requirePostgres("jdbc:h2:mem:epc"));
        assertThrows(IllegalStateException.class, () -> EpcJdbc.requirePostgres("jdbc:derby:memory:epc"));
        assertThrows(IllegalStateException.class, () -> EpcJdbc.requirePostgres("jdbc:sqlite:epc.db"));
        assertThrows(IllegalStateException.class, () -> EpcJdbc.requirePostgres("jdbc:postgresql://postgres:5432/elisa"));
        assertDoesNotThrow(() -> EpcJdbc.requirePostgres(EpcJdbc.DEFAULT_URL));
    }
}
