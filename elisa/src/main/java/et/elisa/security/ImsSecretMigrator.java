package et.elisa.security;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * W2-a boot migration (D6 — drop at migration, fail-closed):
 * 1. legacy plaintext {@code ims_subscriber.ki/.opc} → wrap into {@code ims_secret};
 * 2. drop the plaintext columns;
 * 3. refuse to start when plaintext exists but KEK is unusable.
 */
@ApplicationScoped
public class ImsSecretMigrator {

    private static final Logger LOG = LogManager.getLogger(ImsSecretMigrator.class);

    private final DataSource ds;
    private final ImsSecretStore secrets;

    @Inject
    public ImsSecretMigrator(DataSource ds, ImsSecretStore secrets) {
        this.ds = ds;
        this.secrets = secrets;
    }

    void onStart(@Observes StartupEvent ev) {
        migrate();
    }

    /** @return number of migrated rows. Visible for tests / ops. */
    public int migrate() {
        if (ds == null) {
            return 0;
        }
        try (Connection c = ds.getConnection()) {
            if (!columnExists(c, "ims_subscriber", "ki")) {
                return 0; // already migrated / fresh install
            }
            List<String[]> legacy = new ArrayList<>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT impi, ki, opc FROM ims_subscriber "
                                 + "WHERE ki IS NOT NULL AND ki <> '' FOR UPDATE")) {
                while (rs.next()) {
                    legacy.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
            if (!legacy.isEmpty()) {
                // Fail-closed BEFORE touching anything when KEK cannot unwrap later.
                for (String[] row : legacy) {
                    byte[] probe = ImsSecretCrypto.parseKeyHex(row[1], "legacy ki");
                    if (probe.length != 16) {
                        throw new IllegalStateException("legacy ki not 16 bytes impi=" + row[0]);
                    }
                }
                for (String[] row : legacy) {
                    secrets.putHex(row[0], row[1], row[2]);
                }
            }
            try (Statement st = c.createStatement()) {
                st.execute("ALTER TABLE ims_subscriber DROP COLUMN IF EXISTS ki");
                st.execute("ALTER TABLE ims_subscriber DROP COLUMN IF EXISTS opc");
            }
            LOG.info("ImsSecretMigrator done: wrapped={} rows, plaintext columns dropped", legacy.size());
            return legacy.size();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ImsSecretMigrator failed — refusing plaintext-at-rest boot", e);
        }
    }

    private static boolean columnExists(Connection c, String table, String column)
            throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Test seam — re-run migration against a fresh DataSource. */
    public static int runOnce(DataSource ds, ImsSecretStore store) {
        return new ImsSecretMigrator(ds, store).migrate();
    }
}
