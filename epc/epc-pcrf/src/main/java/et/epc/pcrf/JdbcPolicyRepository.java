package et.epc.pcrf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Postgres durable PCRF store. {@code epc_pcc_rule} is the rule catalog (APN
 * scoped); {@code epc_ipcan_session} holds the per-session decision snapshot.
 * {@code flow_desc_json} is a JSON array; the pilot reads the first element.
 */
public final class JdbcPolicyRepository implements PolicyRepository {

    // Package-private so tests can assert the SQL stays PostgreSQL.
    static final String SQL_LOAD_RULES = """
            SELECT name, qci, arp_priority, preempt_cap, preempt_vuln,
                   mbr_ul_bps, mbr_dl_bps, gbr_ul_bps, gbr_dl_bps,
                   rating_group, service_id, flow_desc_json, gating_open
              FROM epc_pcc_rule
             WHERE apn = ?
             ORDER BY name
            """;

    static final String SQL_UPSERT_SESSION = """
            INSERT INTO epc_ipcan_session (
              session_id, imsi, apn, ue_ipv4, charging_id, decision_json)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (session_id) DO UPDATE SET
              imsi = EXCLUDED.imsi,
              apn = EXCLUDED.apn,
              ue_ipv4 = EXCLUDED.ue_ipv4,
              charging_id = EXCLUDED.charging_id,
              decision_json = EXCLUDED.decision_json,
              updated_at = now()
            """;

    // Active Gx sessions for an admin policy change → RAR fan-out (Phase C3).
    static final String SQL_IPCAN_SESSION_IDS = """
            SELECT session_id
              FROM epc_ipcan_session
             WHERE imsi = ? AND apn = ?
             ORDER BY updated_at DESC
            """;

    private final DataSource ds;

    public JdbcPolicyRepository(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    @Override
    public List<PccRule> loadRules(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        List<PccRule> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_LOAD_RULES)) {
            ps.setString(1, apn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRule(rs));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("pcrf rule load", e);
        }
    }

    @Override
    public void upsertIpCanSession(String sessionId, String imsi, String apn,
                                   String ueIpv4, long chargingId, String decisionJson) {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_UPSERT_SESSION)) {
            ps.setString(1, sessionId);
            ps.setString(2, imsi);
            ps.setString(3, apn);
            ps.setString(4, ueIpv4);
            ps.setLong(5, chargingId);
            ps.setString(6, decisionJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("pcrf ip-can session upsert", e);
        }
    }

    @Override
    public List<String> ipCanSessionIds(String imsi, String apn) {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        List<String> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SQL_IPCAN_SESSION_IDS)) {
            ps.setString(1, imsi);
            ps.setString(2, apn);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("session_id"));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("pcrf ip-can session lookup", e);
        }
    }

    private static PccRule mapRule(ResultSet rs) throws SQLException {
        return new PccRule(
                rs.getString("name"),
                rs.getInt("qci"),
                rs.getInt("arp_priority"),
                rs.getBoolean("preempt_cap"),
                rs.getBoolean("preempt_vuln"),
                rs.getLong("mbr_ul_bps"),
                rs.getLong("mbr_dl_bps"),
                rs.getLong("gbr_ul_bps"),
                rs.getLong("gbr_dl_bps"),
                nullableInt(rs, "rating_group"),
                rs.getString("service_id"),
                firstFlow(rs.getString("flow_desc_json")),
                rs.getBoolean("gating_open"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    // "[\"permit out ip from any to any\"]" -> first JSON string token.
    static String firstFlow(String json) {
        if (json == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return json.trim().isEmpty() ? "" : json.trim();
    }
}