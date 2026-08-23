package et.epc.hss;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PdnType;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    static final String SELECT_FIELDS = """
            imsi, msisdn, enabled, mcc, mnc, default_apn, apns_csv,
            qos_qci, charging_char, sqn, serving_mme_host, purged, ims_scscf_uri,
            impi, impu_csv, service_profile_json, ue_ambr_ul_bps, ue_ambr_dl_bps
            """;

    static final String SAVE_IMS_IDENTITY = """
            UPDATE epc_subscriber
               SET impi = ?, impu_csv = ?, service_profile_json = ?, updated_at = now()
             WHERE imsi = ?
            """;

    /** TS 29.228 IMPU registration state + the S-CSCF stored alongside it (V7 columns). */
    static final String SET_IMPU_REG_STATE = """
            UPDATE epc_subscriber
               SET impu_reg_state = ?, scscf_uri = ?, updated_at = now()
             WHERE imsi = ?
            """;

    /** Registration-state aware S-CSCF location: state column set, legacy column as fallback. */
    static final String LOCATE_SCSCF = """
            SELECT COALESCE(scscf_uri, ims_scscf_uri)
              FROM epc_subscriber WHERE imsi = ?
            """;

    static final String UPSERT_SUBSCRIBER = """
            INSERT INTO epc_subscriber (
              imsi, msisdn, enabled, mcc, mnc, default_apn, apns_csv,
              qos_qci, charging_char, sqn, serving_mme_host, purged,
              ims_scscf_uri, impi, impu_csv, service_profile_json,
              ue_ambr_ul_bps, ue_ambr_dl_bps, key_ref)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (imsi) DO UPDATE SET
              msisdn = EXCLUDED.msisdn,
              enabled = EXCLUDED.enabled,
              default_apn = EXCLUDED.default_apn,
              apns_csv = EXCLUDED.apns_csv,
              serving_mme_host = EXCLUDED.serving_mme_host,
              purged = EXCLUDED.purged,
              ims_scscf_uri = EXCLUDED.ims_scscf_uri,
              impi = EXCLUDED.impi,
              impu_csv = EXCLUDED.impu_csv,
              service_profile_json = EXCLUDED.service_profile_json,
              ue_ambr_ul_bps = EXCLUDED.ue_ambr_ul_bps,
              ue_ambr_dl_bps = EXCLUDED.ue_ambr_dl_bps,
              updated_at = now()
            """;

    static final String SELECT_APN_PROFILES = """
            SELECT apn, pdn_type, qci, arp_priority, preemption_capability,
                   preemption_vulnerability, apn_ambr_ul_bps, apn_ambr_dl_bps,
                   charging_characteristics, default_apn
              FROM epc_apn_profile WHERE imsi = ? ORDER BY apn
            """;

    static final String SELECT_ALL_APN_PROFILES = """
            SELECT imsi, apn, pdn_type, qci, arp_priority, preemption_capability,
                   preemption_vulnerability, apn_ambr_ul_bps, apn_ambr_dl_bps,
                   charging_characteristics, default_apn
              FROM epc_apn_profile ORDER BY imsi, apn
            """;

    static final String UPSERT_APN_PROFILE = """
            INSERT INTO epc_apn_profile (
              imsi, apn, pdn_type, qci, arp_priority, preemption_capability,
              preemption_vulnerability, apn_ambr_ul_bps, apn_ambr_dl_bps,
              charging_characteristics, default_apn, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,now())
            ON CONFLICT (imsi, apn) DO UPDATE SET
              pdn_type = EXCLUDED.pdn_type,
              qci = EXCLUDED.qci,
              arp_priority = EXCLUDED.arp_priority,
              preemption_capability = EXCLUDED.preemption_capability,
              preemption_vulnerability = EXCLUDED.preemption_vulnerability,
              apn_ambr_ul_bps = EXCLUDED.apn_ambr_ul_bps,
              apn_ambr_dl_bps = EXCLUDED.apn_ambr_dl_bps,
              charging_characteristics = EXCLUDED.charging_characteristics,
              default_apn = EXCLUDED.default_apn,
              updated_at = now()
            """;

    static final String DELETE_APN_PROFILE = """
            DELETE FROM epc_apn_profile WHERE imsi = ? AND apn = ?
            """;

    private final DataSource ds;

    public JdbcHssRepository(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    @Override
    public void save(SubscriberRecord rec) {
        SubscriberProfile p = rec.profile();
        String sql = UPSERT_SUBSCRIBER;
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
            ps.setString(14, rec.impi());
            ps.setString(15, csv(rec.impus()));
            ps.setString(16, rec.serviceProfileJson());
            ps.setLong(17, p.subscribedQos().ueAmbrUlBps());
            ps.setLong(18, p.subscribedQos().ueAmbrDlBps());
            ps.setString(19, rec.imsi().digits());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss save", e);
        }
    }

    @Override
    public Optional<SubscriberRecord> find(Imsi imsi) {
        String sql = "SELECT " + SELECT_FIELDS + " FROM epc_subscriber WHERE imsi = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, imsi.digits());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(withApnProfiles(fromRow(rs)));
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
    public Optional<SubscriberRecord> findByImpi(String impi) {
        String norm = norm(impi);
        if (norm.isBlank()) {
            return Optional.empty();
        }
        String user = userPart(norm);
        String sql = "SELECT " + SELECT_FIELDS
                + " FROM epc_subscriber WHERE impi = ? OR imsi = ? OR msisdn = ?";
        return first(sql, norm, user, norm);
    }

    @Override
    public Optional<SubscriberRecord> findByImpu(String impu) {
        String norm = stripSip(impu);
        if (norm.isBlank()) {
            return Optional.empty();
        }
        String user = userPart(norm);
        String sql = "SELECT " + SELECT_FIELDS
                + " FROM epc_subscriber WHERE impi = ? OR imsi = ? OR msisdn = ?";
        Optional<SubscriberRecord> r = first(sql, norm, user, user);
        if (r.isPresent()) {
            return r;
        }
        return first("SELECT " + SELECT_FIELDS + " FROM epc_subscriber WHERE impu_csv LIKE ?",
                "%" + norm + "%").filter(x -> matchesImpu(x, norm));
    }

    @Override
    public void saveImsIdentity(Imsi imsi, String impi, List<String> impus, String serviceProfileJson) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SAVE_IMS_IDENTITY)) {
            ps.setString(1, impi);
            ps.setString(2, csv(impus));
            ps.setString(3, serviceProfileJson);
            ps.setString(4, imsi.digits());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss saveImsIdentity", e);
        }
    }

    @Override
    public void setImpuRegState(Imsi imsi, String state, String scscfUri) {
        String normalized = (state == null || state.isBlank())
                ? "NOT_REGISTERED"
                : state.trim().toUpperCase(java.util.Locale.ROOT);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(SET_IMPU_REG_STATE)) {
            ps.setString(1, normalized);
            ps.setString(2, scscfUri);
            ps.setString(3, imsi.digits());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss setImpuRegState", e);
        }
    }

    @Override
    public String locateScscf(Imsi imsi) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(LOCATE_SCSCF)) {
            ps.setString(1, imsi.digits());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss locateScscf", e);
        }
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

    @Override
    public List<SubscriberRecord> findAll() {
        String sql = "SELECT " + SELECT_FIELDS + " FROM epc_subscriber ORDER BY imsi";
        List<SubscriberRecord> rows = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rows.add(fromRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss findAll", e);
        }
        Map<String, List<ApnProfileRow>> apnRows = allApnProfiles();
        List<SubscriberRecord> out = new ArrayList<>(rows.size());
        for (SubscriberRecord rec : rows) {
            List<ApnProfileRow> apns = apnRows.get(rec.imsi().digits());
            out.add(apns == null || apns.isEmpty() ? rec : rebuildWithApns(rec, apns));
        }
        return out;
    }

    @Override
    public void upsertApnProfile(Imsi imsi, ApnConfig apn, int chargingCharacteristics, boolean defaultApn) {
        Objects.requireNonNull(imsi, "imsi");
        Objects.requireNonNull(apn, "apn");
        QosProfile q = apn.qos();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(UPSERT_APN_PROFILE)) {
            ps.setString(1, imsi.digits());
            ps.setString(2, apn.apn());
            ps.setString(3, apn.pdnType().name());
            ps.setInt(4, q.qci());
            ps.setInt(5, q.arpPriority());
            ps.setBoolean(6, q.preemptionCapability());
            ps.setBoolean(7, q.preemptionVulnerability());
            ps.setLong(8, q.apnAmbrUlBps());
            ps.setLong(9, q.apnAmbrDlBps());
            ps.setInt(10, chargingCharacteristics);
            ps.setBoolean(11, defaultApn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss upsertApnProfile", e);
        }
    }

    @Override
    public void deleteApnProfile(Imsi imsi, String apn) {
        Objects.requireNonNull(imsi, "imsi");
        Objects.requireNonNull(apn, "apn");
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(DELETE_APN_PROFILE)) {
            ps.setString(1, imsi.digits());
            ps.setString(2, apn);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("hss deleteApnProfile", e);
        }
    }

    /**
     * Overlay relational APN configuration profile rows ({@code epc_apn_profile}) onto
     * the subscriber record. No rows for this IMSI keeps the legacy {@code apns_csv}
     * profile parsed by {@link #fromRow} untouched (back-compat).
     */
    private SubscriberRecord withApnProfiles(SubscriberRecord rec) {
        List<ApnProfileRow> rows = apnProfiles(rec.imsi().digits());
        return rows.isEmpty() ? rec : rebuildWithApns(rec, rows);
    }

    private List<ApnProfileRow> apnProfiles(String imsi) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_APN_PROFILES)) {
            ps.setString(1, imsi);
            try (ResultSet rs = ps.executeQuery()) {
                List<ApnProfileRow> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(apnProfileRow(imsi, rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss apn profile load", e);
        }
    }

    private Map<String, List<ApnProfileRow>> allApnProfiles() {
        Map<String, List<ApnProfileRow>> byImsi = new HashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_ALL_APN_PROFILES);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String imsi = rs.getString("imsi");
                byImsi.computeIfAbsent(imsi, k -> new ArrayList<>()).add(apnProfileRow(imsi, rs));
            }
            return byImsi;
        } catch (SQLException e) {
            throw new IllegalStateException("hss apn profile bulk load", e);
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

    /** One {@code epc_apn_profile} row (TS 23.008 §2.3 APN configuration). No secrets. */
    record ApnProfileRow(String imsi, String apn, String pdnType, int qci, int arpPriority,
                         boolean preemptionCapability, boolean preemptionVulnerability,
                         long apnAmbrUlBps, long apnAmbrDlBps, int chargingCharacteristics,
                         boolean defaultApn) {}

    /** Map one relational APN row onto the API {@link ApnConfig}; UE-AMBR rides in from the subscriber row. */
    static ApnConfig toApnConfig(ApnProfileRow row, long ueAmbrUlBps, long ueAmbrDlBps) {
        QosProfile qos = new QosProfile(row.qci(), row.arpPriority(),
                row.preemptionCapability(), row.preemptionVulnerability(),
                row.apnAmbrUlBps(), row.apnAmbrDlBps(), ueAmbrUlBps, ueAmbrDlBps);
        return new ApnConfig(row.apn(), PdnType.valueOf(row.pdnType()), qos,
                "ims".equalsIgnoreCase(row.apn()), null);
    }

    /** First row flagged {@code default_apn} wins (rows arrive ordered by apn); else the subscriber column. */
    static String resolveDefaultApn(String subscriberDefault, List<ApnProfileRow> rows) {
        for (ApnProfileRow r : rows) {
            if (r.defaultApn()) {
                return r.apn();
            }
        }
        return subscriberDefault;
    }

    private static SubscriberRecord rebuildWithApns(SubscriberRecord rec, List<ApnProfileRow> rows) {
        SubscriberProfile p = rec.profile();
        QosProfile sub = p.subscribedQos();
        List<ApnConfig> apns = new ArrayList<>(rows.size());
        for (ApnProfileRow r : rows) {
            apns.add(toApnConfig(r, sub.ueAmbrUlBps(), sub.ueAmbrDlBps()));
        }
        SubscriberProfile np = new SubscriberProfile(p.imsi(), p.msisdn(), p.enabled(), p.homePlmn(),
                apns, resolveDefaultApn(p.defaultApn(), rows), sub, p.chargingCharacteristics());
        SubscriberRecord out = new SubscriberRecord(np, rec.sqn());
        out.setServingMmeHost(rec.servingMmeHost());
        out.setPurged(rec.purged());
        out.setImsScscfUri(rec.imsScscfUri());
        out.setImpi(rec.impi());
        out.setImpus(rec.impus());
        out.setServiceProfileJson(rec.serviceProfileJson());
        return out;
    }

    private static ApnProfileRow apnProfileRow(String imsi, ResultSet rs) throws SQLException {
        return new ApnProfileRow(imsi, rs.getString("apn"), rs.getString("pdn_type"),
                rs.getInt("qci"), rs.getInt("arp_priority"),
                rs.getBoolean("preemption_capability"), rs.getBoolean("preemption_vulnerability"),
                rs.getLong("apn_ambr_ul_bps"), rs.getLong("apn_ambr_dl_bps"),
                rs.getInt("charging_characteristics"), rs.getBoolean("default_apn"));
    }

    private static SubscriberRecord fromRow(ResultSet rs) throws SQLException {
        Imsi imsi = new Imsi(rs.getString("imsi"));
        PlmnId plmn = new PlmnId(rs.getString("mcc"), rs.getString("mnc"));
        List<ApnConfig> apns = parseApns(rs.getString("apns_csv"));
        SubscriberProfile p = new SubscriberProfile(
                imsi, rs.getString("msisdn"), rs.getBoolean("enabled"), plmn, apns,
                rs.getString("default_apn"), qos(rs), rs.getInt("charging_char"));
        SubscriberRecord rec = new SubscriberRecord(p, rs.getLong("sqn"));
        rec.setServingMmeHost(rs.getString("serving_mme_host"));
        rec.setPurged(rs.getBoolean("purged"));
        rec.setImsScscfUri(rs.getString("ims_scscf_uri"));
        rec.setImpi(rs.getString("impi"));
        rec.setImpus(parseCsvList(rs.getString("impu_csv")));
        rec.setServiceProfileJson(rs.getString("service_profile_json"));
        return rec;
    }

    private static QosProfile qos(ResultSet rs) throws SQLException {
        QosProfile pilot = QosProfile.pilotDefault();
        long ul = rs.getLong("ue_ambr_ul_bps");
        boolean ulNull = rs.wasNull();
        long dl = rs.getLong("ue_ambr_dl_bps");
        boolean dlNull = rs.wasNull();
        return new QosProfile(
                rs.getInt("qos_qci"),
                pilot.arpPriority(),
                pilot.preemptionCapability(),
                pilot.preemptionVulnerability(),
                pilot.apnAmbrUlBps(),
                pilot.apnAmbrDlBps(),
                ulNull ? pilot.ueAmbrUlBps() : ul,
                dlNull ? pilot.ueAmbrDlBps() : dl);
    }

    private Optional<SubscriberRecord> first(String sql, String... params) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("hss lookup", e);
        }
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

    private static String csv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(",", values);
    }

    private static List<String> parseCsvList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static boolean matchesImpu(SubscriberRecord rec, String norm) {
        for (String v : rec.impus()) {
            if (stripSip(v).equalsIgnoreCase(norm)) {
                return true;
            }
        }
        return rec.impi() != null && stripSip(rec.impi()).equalsIgnoreCase(norm);
    }

    private static String norm(String v) {
        return v == null ? "" : v.trim();
    }

    private static String stripSip(String v) {
        String s = norm(v);
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            return s.substring(4);
        }
        return s;
    }

    private static String userPart(String v) {
        String s = stripSip(v);
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }
}
