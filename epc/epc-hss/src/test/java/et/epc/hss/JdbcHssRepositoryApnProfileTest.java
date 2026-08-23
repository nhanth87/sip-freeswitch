package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.PdnType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no embedded Postgres — mirrors {@link JdbcHssRepositoryTest}):
 * the {@code epc_apn_profile} row→{@link ApnConfig} mapping plus SQL-shape pins for
 * the Phase A APN configuration profile table (TS 23.008 §2.3).
 */
class JdbcHssRepositoryApnProfileTest {

    private static final String IMSI = "001010000000001";

    private static JdbcHssRepository.ApnProfileRow row(String apn, boolean defaultApn) {
        return new JdbcHssRepository.ApnProfileRow(IMSI, apn, "IPV4V6", 9, 8,
                false, true, 1_000_000_000L, 2_000_000_000L, 0, defaultApn);
    }

    @Test
    void apnProfileRowMapsToApnConfig() {
        ApnConfig a = JdbcHssRepository.toApnConfig(row("internet", true),
                5_000_000_000L, 6_000_000_000L);
        assertEquals("internet", a.apn());
        assertEquals(PdnType.IPV4V6, a.pdnType());
        assertFalse(a.ims());
        assertEquals(9, a.qos().qci());
        assertEquals(8, a.qos().arpPriority());
        assertFalse(a.qos().preemptionCapability());
        assertTrue(a.qos().preemptionVulnerability());
        assertEquals(1_000_000_000L, a.qos().apnAmbrUlBps());
        assertEquals(2_000_000_000L, a.qos().apnAmbrDlBps());
        // UE-AMBR rides in from the subscriber row, never the APN row.
        assertEquals(5_000_000_000L, a.qos().ueAmbrUlBps());
        assertEquals(6_000_000_000L, a.qos().ueAmbrDlBps());
    }

    @Test
    void imsApnRowSetsImsFlag() {
        assertTrue(JdbcHssRepository.toApnConfig(row("ims", false), 1L, 1L).ims());
        assertTrue(JdbcHssRepository.toApnConfig(row("IMS", false), 1L, 1L).ims());
        assertFalse(JdbcHssRepository.toApnConfig(row("internet", false), 1L, 1L).ims());
    }

    @Test
    void resolveDefaultApnPrefersFirstFlaggedRow() {
        List<JdbcHssRepository.ApnProfileRow> rows =
                List.of(row("a", false), row("b", true), row("c", true));
        assertEquals("b", JdbcHssRepository.resolveDefaultApn("fallback", rows));
    }

    @Test
    void resolveDefaultApnFallsBackToSubscriberColumn() {
        assertEquals("fallback",
                JdbcHssRepository.resolveDefaultApn("fallback", List.of(row("a", false))));
        assertEquals("fallback", JdbcHssRepository.resolveDefaultApn("fallback", List.of()));
    }

    /**
     * Round-trip contract without an embedded Postgres: every column the APN
     * {@code find()} path reads back must be written by the {@code upsertApnProfile}
     * INSERT, so a persisted APN profile never silently loses a field on read-back.
     */
    @Test
    void upsertApnProfileWritesEveryColumnSelectReadsBack() {
        Set<String> readCols = csvColumns(selectColumns(JdbcHssRepository.SELECT_APN_PROFILES));
        Set<String> writeCols = csvColumns(insertColumns(JdbcHssRepository.UPSERT_APN_PROFILE));
        for (String col : readCols) {
            assertTrue(writeCols.contains(col),
                    "epc_apn_profile SELECT column '" + col + "' must be written by upsertApnProfile");
        }
        assertTrue(writeCols.contains("imsi"), "upsert keys the row on imsi");
        assertTrue(writeCols.contains("apn"), "upsert keys the row on apn");
    }

    @Test
    void bulkSelectReadsSameColumnsAsScopedSelectPlusImsi() {
        Set<String> scoped = csvColumns(selectColumns(JdbcHssRepository.SELECT_APN_PROFILES));
        Set<String> bulk = csvColumns(selectColumns(JdbcHssRepository.SELECT_ALL_APN_PROFILES));
        assertTrue(bulk.contains("imsi"), "boot-warm bulk load must group rows by imsi");
        bulk.remove("imsi");
        assertEquals(scoped, bulk, "boot-warm bulk load must read exactly the scoped columns");
    }

    /** Both APN selects must be deterministic so ULA subscription data is stable. */
    @Test
    void apnSelectsAreOrdered() {
        assertTrue(JdbcHssRepository.SELECT_APN_PROFILES.contains("ORDER BY apn"));
        assertTrue(JdbcHssRepository.SELECT_ALL_APN_PROFILES.contains("ORDER BY imsi, apn"));
    }

    @Test
    void upsertApnProfileConflictKeyIsImsiApn() {
        assertTrue(JdbcHssRepository.UPSERT_APN_PROFILE.contains("ON CONFLICT (imsi, apn)"));
        assertTrue(JdbcHssRepository.UPSERT_APN_PROFILE.contains("updated_at = now()"));
    }

    @Test
    void deleteApnProfileKeysOnImsiAndApn() {
        assertTrue(JdbcHssRepository.DELETE_APN_PROFILE.contains("DELETE FROM epc_apn_profile"));
        assertTrue(JdbcHssRepository.DELETE_APN_PROFILE.contains("imsi = ?"));
        assertTrue(JdbcHssRepository.DELETE_APN_PROFILE.contains("apn = ?"));
    }

    /** APN configuration is identity/QoS only — secret material lives in {@code epc_secret}. */
    @Test
    void apnProfileNeverCarriesSecretMaterial() {
        Set<String> writeCols = csvColumns(insertColumns(JdbcHssRepository.UPSERT_APN_PROFILE));
        assertFalse(writeCols.contains("ki"));
        assertFalse(writeCols.contains("opc"));
        assertFalse(writeCols.contains("amf"));
        assertFalse(writeCols.contains("ki_enc"));
        assertFalse(writeCols.contains("opc_enc"));
        assertFalse(writeCols.contains("amf_enc"));
        assertFalse(writeCols.contains("key_ref"));
    }

    private static String selectColumns(String sql) {
        return sql.substring(sql.indexOf("SELECT") + "SELECT".length(), sql.indexOf(" FROM"));
    }

    private static String insertColumns(String sql) {
        String header = sql.substring(sql.indexOf("epc_apn_profile ("));
        return header.substring(header.indexOf('(') + 1, header.indexOf(')'));
    }

    private static Set<String> csvColumns(String csv) {
        Set<String> cols = new HashSet<>();
        for (String c : csv.split(",")) {
            if (!c.isBlank()) {
                cols.add(c.trim());
            }
        }
        return cols;
    }
}
