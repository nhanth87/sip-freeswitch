package et.epc.api.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Postgres durable session checkpoint sink. Off-hot-path only — GTP receive
 * loops must never call this synchronously. The table columns mirror
 * {@link SessionCheckpoint} one-to-one; {@code session_id} is role-namespaced
 * ({@code sgw|imsi/apn/ebi}).
 */
public final class JdbcDurableSink implements DurableSink {

    // Package-private so cluster tests can assert the SQL stays PostgreSQL.
    static final String SQL_UPSERT = """
            INSERT INTO epc_session_checkpoint (
              session_id, role, imsi, apn, ebi, owner_node_id, generation,
              ue_ipv4, sgw_s1u_key, sgw_s5u_key, pgw_s5u_key, charging_id,
              pfcp_seid_hex, s11c_teid, pfcp_seid)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (session_id) DO UPDATE SET
              role = EXCLUDED.role,
              imsi = EXCLUDED.imsi,
              apn = EXCLUDED.apn,
              ebi = EXCLUDED.ebi,
              owner_node_id = EXCLUDED.owner_node_id,
              generation = EXCLUDED.generation,
              ue_ipv4 = EXCLUDED.ue_ipv4,
              sgw_s1u_key = EXCLUDED.sgw_s1u_key,
              sgw_s5u_key = EXCLUDED.sgw_s5u_key,
              pgw_s5u_key = EXCLUDED.pgw_s5u_key,
              charging_id = EXCLUDED.charging_id,
              pfcp_seid_hex = EXCLUDED.pfcp_seid_hex,
              s11c_teid = EXCLUDED.s11c_teid,
              pfcp_seid = EXCLUDED.pfcp_seid
            """;

    static final String SQL_DELETE = """
            DELETE FROM epc_session_checkpoint WHERE session_id = ?
            """;

    private final DataSource ds;

    public JdbcDurableSink(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    static final String SQL_LOAD = """
            SELECT session_id, role, imsi, apn, ebi, owner_node_id, generation,
                   ue_ipv4, sgw_s1u_key, sgw_s5u_key, pgw_s5u_key, charging_id,
                   pfcp_seid_hex, s11c_teid, pfcp_seid
              FROM epc_session_checkpoint
             WHERE session_id = ?
            """;

    static final String SQL_LOAD_BY_OWNER = """
            SELECT session_id, role, imsi, apn, ebi, owner_node_id, generation,
                   ue_ipv4, sgw_s1u_key, sgw_s5u_key, pgw_s5u_key, charging_id,
                   pfcp_seid_hex, s11c_teid, pfcp_seid
              FROM epc_session_checkpoint
             WHERE owner_node_id = ?
             ORDER BY session_id
            """;

    static final String SQL_FIND_BY_S11C_TEID = """
            SELECT session_id, role, imsi, apn, ebi, owner_node_id, generation,
                   ue_ipv4, sgw_s1u_key, sgw_s5u_key, pgw_s5u_key, charging_id,
                   pfcp_seid_hex, s11c_teid, pfcp_seid
              FROM epc_session_checkpoint
             WHERE s11c_teid = ? AND role = 'sgw'
             LIMIT 1
            """;

    @Override
    public void checkpoint(SessionCheckpoint session) {
        Objects.requireNonNull(session);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_UPSERT)) {
            ps.setString(1, session.sessionId());
            ps.setString(2, session.role());
            ps.setString(3, session.imsiDigits());
            ps.setString(4, session.apn());
            ps.setInt(5, session.ebi());
            ps.setString(6, session.ownerNodeId());
            ps.setLong(7, session.generation());
            ps.setString(8, session.ueIpv4());
            ps.setString(9, session.sgwS1uKey());
            ps.setString(10, session.sgwS5uKey());
            ps.setString(11, session.pgwS5uKey());
            ps.setLong(12, session.chargingId());
            ps.setString(13, session.pfcpSeidHex());
            ps.setInt(14, session.s11cTeid());
            ps.setLong(15, session.pfcpSeid());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("epc session checkpoint upsert", e);
        }
    }

    @Override
    public void forget(String sessionId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_DELETE)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("epc session checkpoint forget", e);
        }
    }

    @Override
    public Optional<SessionCheckpoint> load(String sessionId) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_LOAD)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("epc session checkpoint load", e);
        }
    }

    @Override
    public List<SessionCheckpoint> loadByOwner(String nodeId) {
        List<SessionCheckpoint> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_LOAD_BY_OWNER)) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("epc session checkpoint load-by-owner", e);
        }
    }

    @Override
    public Optional<SessionCheckpoint> findByS11cTeid(int s11cTeid) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SQL_FIND_BY_S11C_TEID)) {
            ps.setInt(1, s11cTeid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(map(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("epc session checkpoint find-by-s11c-teid", e);
        }
    }

    private static SessionCheckpoint map(ResultSet rs) throws SQLException {
        return new SessionCheckpoint(
                rs.getString("session_id"),
                rs.getString("role"),
                rs.getString("imsi"),
                rs.getString("apn"),
                rs.getInt("ebi"),
                rs.getString("owner_node_id"),
                rs.getLong("generation"),
                rs.getString("ue_ipv4"),
                rs.getString("sgw_s1u_key"),
                rs.getString("sgw_s5u_key"),
                rs.getString("pgw_s5u_key"),
                rs.getLong("charging_id"),
                rs.getString("pfcp_seid_hex"),
                rs.getInt("s11c_teid"),
                rs.getLong("pfcp_seid"));
    }
}