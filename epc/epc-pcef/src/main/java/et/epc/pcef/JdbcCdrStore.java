package et.epc.pcef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/** Postgres Gz CDR store — durable billing record, never heap. */
public final class JdbcCdrStore implements CdrStore {

    private final DataSource ds;

    public JdbcCdrStore(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    @Override
    public void persist(Cdr cdr) {
        String sql = """
                INSERT INTO epc_pcef_cdr (
                  charging_id, imsi, apn, uplink_octets, downlink_octets, started_at, ended_at, cdr_kind)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (charging_id) DO UPDATE SET
                  imsi = EXCLUDED.imsi,
                  apn = EXCLUDED.apn,
                  uplink_octets = EXCLUDED.uplink_octets,
                  downlink_octets = EXCLUDED.downlink_octets,
                  started_at = EXCLUDED.started_at,
                  ended_at = EXCLUDED.ended_at,
                  cdr_kind = EXCLUDED.cdr_kind
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cdr.chargingId().value());
            ps.setString(2, cdr.imsi().digits());
            ps.setString(3, cdr.apn());
            ps.setLong(4, cdr.uplinkOctets());
            ps.setLong(5, cdr.downlinkOctets());
            ps.setTimestamp(6, Timestamp.from(cdr.start()));
            ps.setTimestamp(7, Timestamp.from(cdr.end()));
            ps.setString(8, cdr.kind() == null ? Cdr.Kind.STOP.name() : cdr.kind().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("pcef cdr persist", e);
        }
    }

    @Override
    public List<Cdr> list() {
        String sql = """
                SELECT charging_id, imsi, apn, uplink_octets, downlink_octets, started_at, ended_at, cdr_kind
                  FROM epc_pcef_cdr ORDER BY started_at
                """;
        List<Cdr> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Cdr(
                        new ChargingId(rs.getLong("charging_id")),
                        new et.epc.api.Imsi(rs.getString("imsi")),
                        rs.getString("apn"),
                        rs.getLong("uplink_octets"),
                        rs.getLong("downlink_octets"),
                        toInstant(rs, "started_at"),
                        toInstant(rs, "ended_at"),
                        Cdr.Kind.valueOf(rs.getString("cdr_kind"))));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("pcef cdr list", e);
        }
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? Instant.EPOCH : t.toInstant();
    }
}