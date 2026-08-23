package et.epc.host;

import et.epc.up.linux.UrrDatastore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Postgres-backed URR baseline store (table {@code epc_urr_baseline},
 * {@code V5__epc_urr_baseline.sql}). Off-hot-path only: the poller persists at
 * most once per {@code epc.up.urr.baseline.persist-ms}, never per packet.
 *
 * <p>JDBC-only on purpose: the baseline is a single-writer durable counter and
 * {@link EpcInfinispanManager} defines no cache for it, so an ISPN-first
 * dual-write would add a copy with no reader. Postgres is the SoT.
 */
public final class JdbcUrrDatastore implements UrrDatastore {

    static final String SQL_UPSERT = """
            INSERT INTO epc_urr_baseline (urr_id, baseline_bytes, updated_at)
            VALUES (?, ?, now())
            ON CONFLICT (urr_id) DO UPDATE SET
              baseline_bytes = EXCLUDED.baseline_bytes,
              updated_at = now()
            """;

    static final String SQL_LOAD = """
            SELECT baseline_bytes
              FROM epc_urr_baseline
             WHERE urr_id = ?
            """;

    static final String SQL_DELETE = """
            DELETE FROM epc_urr_baseline WHERE urr_id = ?
            """;

    private final DataSource dataSource;

    public JdbcUrrDatastore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void saveBaseline(String urrId, long baselineBytes) {
        Objects.requireNonNull(urrId, "urrId");
        if (baselineBytes < 0) {
            throw new IllegalArgumentException("baselineBytes must be non-negative");
        }
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_UPSERT)) {
            ps.setString(1, urrId);
            ps.setLong(2, baselineBytes);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("urr baseline save failed: " + urrId, e);
        }
    }

    @Override
    public Optional<Long> loadBaseline(String urrId) {
        Objects.requireNonNull(urrId, "urrId");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_LOAD)) {
            ps.setString(1, urrId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong("baseline_bytes")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("urr baseline load failed: " + urrId, e);
        }
    }

    @Override
    public void delete(String urrId) {
        Objects.requireNonNull(urrId, "urrId");
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setString(1, urrId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("urr baseline delete failed: " + urrId, e);
        }
    }
}
