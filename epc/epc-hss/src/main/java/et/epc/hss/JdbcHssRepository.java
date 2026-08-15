package et.epc.hss;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
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
 * Postgres HSS. SQN uses {@code UPDATE … RETURNING} so two AIR never share a vector.
 * Ki/OPc stay in {@link SecretStore} ({@code key_ref} only on this row).
 */
public final class JdbcHssRepository implements HssRepository {

    static final String RESERVE_SQN = """
            UPDATE epc_subscriber SET sqn = sqn + 1, updated_at = now()
             WHERE imsi = ? RETURNING sqn
            """;

    private final DataSource ds;

    public JdbcHssRepository(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    @Override
    public void save(SubscriberRecord rec) {
        SubscriberProfile p = rec.profile();
        String sql = """
                INSERT INTO epc_subscriber (
                  imsi, msisdn, enabled, mcc, mnc, default_apn, apns_csv,
                  qos_qci, charging_char, sqn, serving_mme_host, purged,
                  ims_scscf_uri, key_ref)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (imsi) DO UPDATE SET
                  msisdn = EXCLUDED.msisdn,
                  enabled = EXCLUDED.enabled,
                  default_apn = EXCLUDED.default_apn,
                  apns_csv = EXCLUDED.apns_csv,
                  serving_mme_host = EXCLUDED.serving_mme_host,
                  purged = EXCLUDED.purged,
                  ims_scscf_uri = EXCLUDED.ims_scscf_uri,
                  updated_at = now()
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p.imsi().digits());
            ps.setString(2, p.msisdn());
            ps.setBoolean(3, p.enabled());
            ps.setString(4, p.homePlmn().mcc());
            ps.setString(5, p.homePlmn().mnc());
            ps.setString(6, p.defaultApn());
            ps.setString(7, apnCsv(p));
            ps.setInt(8, p.subscribedQos().qci());
            ps.setInt(9, p.chargingCharacteristics());
            ps.setLong(10, rec.sqn());
            ps.setString(11, rec.servingMmeHost());
            ps.setBoolean(12, rec.purged());
            ps.setString(13, rec.imsScscfUri());
            ps.setString(14, rec.imsi().digits());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss save", e);
        }
    }

    @Override
    public Optional<SubscriberRecord> find(Imsi imsi) {
        String sql = """
                SELECT imsi, msisdn, enabled, mcc, mnc, default_apn, apns_csv,
                       qos_qci, charging_char, sqn, serving_mme_host, purged, ims_scscf_uri
                  FROM epc_subscriber WHERE imsi = ?
                """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, imsi.digits());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(fromRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss find", e);
        }
    }

    @Override
    public long reserveSqn(Imsi imsi) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(RESERVE_SQN)) {
            ps.setString(1, imsi.digits());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("unknown IMSI");
                }
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss reserveSqn", e);
        }
    }

    @Override
    public void forceSqn(Imsi imsi, long next) {
        String sql = "UPDATE epc_subscriber SET sqn = GREATEST(sqn, ?), updated_at = now() WHERE imsi = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, next);
            ps.setString(2, imsi.digits());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss forceSqn", e);
        }
    }

    @Override
    public void setServingMme(Imsi imsi, String host) {
        update("UPDATE epc_subscriber SET serving_mme_host = ?, updated_at = now() WHERE imsi = ?",
                host, imsi.digits());
    }

    @Override
    public void setPurged(Imsi imsi, boolean purged) {
        String sql = "UPDATE epc_subscriber SET purged = ?, updated_at = now() WHERE imsi = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, purged);
            ps.setString(2, imsi.digits());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss setPurged", e);
        }
    }

    @Override
    public void setImsScscf(Imsi imsi, String uri) {
        update("UPDATE epc_subscriber SET ims_scscf_uri = ?, updated_at = now() WHERE imsi = ?",
                uri, imsi.digits());
    }

    @Override
    public int size() {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM epc_subscriber");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("hss size", e);
        }
    }

    private void update(String sql, String v, String imsi) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, v);
            ps.setString(2, imsi);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss update", e);
        }
    }

    private static SubscriberRecord fromRow(ResultSet rs) throws SQLException {
        Imsi imsi = new Imsi(rs.getString("imsi"));
        PlmnId plmn = new PlmnId(rs.getString("mcc"), rs.getString("mnc"));
        List<ApnConfig> apns = parseApns(rs.getString("apns_csv"));
        SubscriberProfile p = new SubscriberProfile(
                imsi, rs.getString("msisdn"), rs.getBoolean("enabled"), plmn, apns,
                rs.getString("default_apn"), QosProfile.pilotDefault(), rs.getInt("charging_char"));
        SubscriberRecord rec = new SubscriberRecord(p, rs.getLong("sqn"));
        rec.setServingMmeHost(rs.getString("serving_mme_host"));
        rec.setPurged(rs.getBoolean("purged"));
        rec.setImsScscfUri(rs.getString("ims_scscf_uri"));
        return rec;
    }

    private static String apnCsv(SubscriberProfile p) {
        StringBuilder b = new StringBuilder();
        for (ApnConfig a : p.apns()) {
            if (!b.isEmpty()) {
                b.append(',');
            }
            b.append(a.apn());
            if (a.ims()) {
                b.append(":ims");
            }
        }
        return b.toString();
    }

    private static List<ApnConfig> parseApns(String csv) {
        List<ApnConfig> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            out.add(ApnConfig.internet());
            return out;
        }
        for (String part : csv.split(",")) {
            String[] bits = part.split(":");
            if (bits.length > 1 && "ims".equalsIgnoreCase(bits[1])) {
                out.add(ApnConfig.ims("192.0.2.10"));
            } else {
                out.add(ApnConfig.internet());
            }
        }
        return out;
    }
}
