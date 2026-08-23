package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.pcrf.PcrfService;
import jakarta.ws.rs.WebApplicationException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Unit tests for {@link EpcConfigResource} — GET effective view + PUT validation/apply. No CDI/HTTP. */
class EpcConfigResourceTest {

    @BeforeEach
    void clearOverrides() {
        EpcConfigResource.OVERRIDES.clear();
    }

    private Map<String, Object> build(boolean hss, boolean sgwc, boolean pgwc,
                                      boolean pcrf, boolean pcef, boolean up) {
        return EpcConfigResource.effective(
                8099, true, EpcRoles.fromEnv(hss, sgwc, pgwc, pcrf, pcef, up),
                "LOCAL", "epc-cluster", "epc-1", 1,
                "127.0.0.1", 2123, 2123,
                "127.0.0.1", 8805, 8805,
                false, "127.0.0.1", 2123,
                EpcJdbc.DEFAULT_URL, 7200L,
                "127.0.0.1", 3868, "client", "", "", "", "epc.lab", "tcp",
                "MBB", 1_000_000_000L, true, true,
                "", false, false, "poll", 60000L, 300000L, false,
                "127.0.0.1", 8805, "127.0.0.1", 8805,
                1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L,
                "10.64.0.0/16", "2001:db8::/64");
    }

    @Test
    @SuppressWarnings("unchecked")
    void exposesAllConfiguredKeys() {
        Map<String, Object> m = build(true, false, false, false, false, false);
        String[] expected = {
                "httpPort", "datastoreEnabled", "roles", "clusterMode", "clusterName",
                "nodeId", "nodeIndex", "ttlSeconds",
                "gtpPeerHost", "gtpPeerPort", "gtpBindPort",
                "pfcpPeerHost", "pfcpPeerPort", "pfcpBindPort",
                "s5cWire", "s5cPgwcPeerHost", "s5cPgwcPeerPort",
                "jdbcUrl", "jdbcScheme",
                "diameterPeerHost", "diameterPeerPort", "diameterPeerRole",
                "diameterDestHost", "diameterDestRealm", "diameterOriginHost",
                "diameterOriginRealm", "diameterTransport",
                "pcrfDefaultQos", "pcrfUsageMonitorThreshold", "pcrfRxEnabled", "pcefCdrEnabled",
                "hssMmeAllowlistEnabled", "hssMmeAllowlist",
                "upQerEnabled", "upUrrMode", "upUrrPollMs", "upUrrBaselinePersistMs",
                "pcefOcsEnabled",
                "sgwuPeerHost", "sgwuPeerPort", "pgwuPeerHost", "pgwuPeerPort",
                "ambr", "pdnPool",
                "effective"};
        for (String k : expected) {
            assertTrue(m.containsKey(k), "missing key " + k);
        }
        assertEquals(45, m.size());

        assertEquals(8099, entry(m, "httpPort").get("value"));
        assertEquals(Boolean.TRUE, entry(m, "datastoreEnabled").get("value"));
        assertEquals("postgresql", entry(m, "jdbcScheme").get("value"));
        assertEquals(EpcJdbc.DEFAULT_URL, entry(m, "jdbcUrl").get("value"));
        assertEquals("tcp", entry(m, "diameterTransport").get("value"));
        assertEquals("epc.lab", entry(m, "diameterOriginRealm").get("value"));

        // P1 knobs surface on the dashboard with neutral defaults.
        assertEquals(Boolean.FALSE, entry(m, "hssMmeAllowlistEnabled").get("value"));
        assertEquals(Boolean.FALSE, entry(m, "upQerEnabled").get("value"));
        assertEquals("poll", entry(m, "upUrrMode").get("value"));
        assertEquals(60000L, entry(m, "upUrrPollMs").get("value"));
        assertEquals(300000L, entry(m, "upUrrBaselinePersistMs").get("value"));
        assertEquals(Boolean.FALSE, entry(m, "pcefOcsEnabled").get("value"));

        Map<String, Object> roles = (Map<String, Object>) entry(m, "roles").get("value");
        assertEquals(Boolean.TRUE, roles.get("hss"));
        assertEquals(Boolean.FALSE, roles.get("sgwc"));
        assertEquals(Boolean.FALSE, roles.get("pgwc"));
        assertEquals(Boolean.FALSE, roles.get("pcrf"));
        assertEquals(Boolean.FALSE, roles.get("pcef"));
        assertEquals(Boolean.FALSE, roles.get("up"));
    }

    @Test
    void bootTimeEntriesAreReadOnlyAndRuntimeKnobsWritable() {
        Map<String, Object> m = build(true, true, true, true, true, true);
        Set<String> mutable = Set.of("pcrfUsageMonitorThreshold", "upUrrMode", "upUrrPollMs");
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Map<String, Object> entry = entry(m, e.getKey());
            assertEquals(mutable.contains(e.getKey()), entry.get("writable"),
                    e.getKey() + " writable flag");
            assertTrue(entry.containsKey("doc"), e.getKey() + " must carry a doc");
        }
        // Runtime knob values surface on the dashboard.
        assertEquals(1_000_000_000L, entry(m, "pcrfUsageMonitorThreshold").get("value"));
        assertEquals("poll", entry(m, "upUrrMode").get("value"));
        assertEquals(60000L, entry(m, "upUrrPollMs").get("value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void effectiveTopologyIsTruthful() {
        Map<String, Object> eff = (Map<String, Object>)
                build(true, true, true, true, true, true).get("effective");
        assertEquals("LOCAL", eff.get("isptMode"));
        assertTrue(((java.util.List<String>) eff.get("cacheNames")).contains("epc-sessions"));

        Map<String, Object> apps = (Map<String, Object>) eff.get("applications");
        // HSS serves S6a + Cx; Gx/Rx route via the host gateway PcrfPort -> PcrfSbb.
        assertEquals(Boolean.TRUE, ((Map<String, Object>) apps.get("s6a")).get("routed"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) apps.get("cx")).get("routed"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) apps.get("gx")).get("routed"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) apps.get("rx")).get("routed"));

        Map<String, Object> gxRx = (Map<String, Object>) eff.get("gxRx");
        assertEquals(Boolean.TRUE, gxRx.get("routedGx"));
        assertEquals(Boolean.TRUE, gxRx.get("routedRx"));
        assertFalse(((String) gxRx.get("note")).isBlank());

        Map<String, Object> s5c = (Map<String, Object>) eff.get("s5c");
        assertEquals(Boolean.FALSE, s5c.get("wireMode"));
        assertEquals("in-process (lab embedded)", s5c.get("mode"));
        assertEquals("127.0.0.1", s5c.get("pgwcPeerHost"));
        assertEquals(2123, s5c.get("pgwcPeerPort"));

        Map<String, Object> up = (Map<String, Object>) eff.get("userPlane");
        assertEquals(Boolean.TRUE, up.get("roles"));
        assertEquals(Boolean.FALSE, up.get("qerEnabled"));
        assertEquals("poll", up.get("urrMode"));
        assertEquals(60000L, ((Map<String, Object>) up.get("urr")).get("pollMs"));
        assertEquals(300000L, ((Map<String, Object>) up.get("urr")).get("baselinePersistMs"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void perServiceRoleFlagsAreTruthful() {
        // PCRF-only node: Gx and Rx route, S6a/Cx do not.
        Map<String, Object> m = build(false, false, false, true, false, false);
        Map<String, Object> roles = (Map<String, Object>) entry(m, "roles").get("value");
        assertEquals(Boolean.TRUE, roles.get("pcrf"));
        assertEquals(Boolean.FALSE, roles.get("pcef"));

        Map<String, Object> eff = (Map<String, Object>) m.get("effective");
        Map<String, Object> apps = (Map<String, Object>) eff.get("applications");
        assertEquals(Boolean.FALSE, ((Map<String, Object>) apps.get("s6a")).get("routed"));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) apps.get("cx")).get("routed"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) apps.get("gx")).get("routed"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) apps.get("rx")).get("routed"));
    }

    // ── PUT validation + apply (Phase D runtime seam) ─────────────────────────

    @Test
    void putThresholdAppliesToLivePcrfService() {
        PcrfService svc = PcrfService.withHeapPolicy();
        EpcConfigResource.RuntimeOverrides ov = new EpcConfigResource.RuntimeOverrides();
        Map<String, Object> r = EpcConfigResource.applyUsageMonitorThreshold(
                EpcConfigResource.KEY_USAGE_MONITOR_THRESHOLD, "2000000", svc, ov);
        assertEquals(EpcConfigResource.KEY_USAGE_MONITOR_THRESHOLD, r.get("key"));
        assertEquals(2_000_000L, r.get("value"));
        assertEquals(Boolean.TRUE, r.get("applied"));
        assertEquals(2_000_000L, svc.usageMonitorThreshold());
        assertNull(ov.usageMonitorThreshold()); // applied live — no override needed
    }

    @Test
    void putThresholdWithoutServiceStoresOverride() {
        EpcConfigResource.RuntimeOverrides ov = new EpcConfigResource.RuntimeOverrides();
        Map<String, Object> r = EpcConfigResource.applyUsageMonitorThreshold(
                EpcConfigResource.KEY_USAGE_MONITOR_THRESHOLD, "5", null, ov);
        assertEquals(Boolean.FALSE, r.get("applied"));
        assertEquals(5L, ov.usageMonitorThreshold());
        assertNotNull(r.get("note"));
    }

    @Test
    void putThresholdRejectsNegativeAndNonNumeric() {
        EpcConfigResource.RuntimeOverrides ov = new EpcConfigResource.RuntimeOverrides();
        String k = EpcConfigResource.KEY_USAGE_MONITOR_THRESHOLD;
        assertStatus(400, () -> EpcConfigResource.applyUsageMonitorThreshold(k, "-1", null, ov));
        assertStatus(400, () -> EpcConfigResource.applyUsageMonitorThreshold(k, "abc", null, ov));
    }

    @Test
    void putUrrModeValidationAndApplySemantics() {
        EpcConfigResource.RuntimeOverrides ov = new EpcConfigResource.RuntimeOverrides();
        String k = EpcConfigResource.KEY_UP_URR_MODE;
        assertStatus(400, () -> EpcConfigResource.applyUrrMode(k, "weird", null, ov));
        assertStatus(400, () -> EpcConfigResource.applyUrrMode(k, "", null, ov));

        // No poller wired at boot: poll stores an override (applied=false)…
        Map<String, Object> poll = EpcConfigResource.applyUrrMode(k, "POLL", null, ov);
        assertEquals("poll", poll.get("value"));
        assertEquals(Boolean.FALSE, poll.get("applied"));
        assertEquals("poll", ov.urrMode());

        // …while off is trivially satisfied (nothing runs).
        Map<String, Object> off = EpcConfigResource.applyUrrMode(k, "off", null, ov);
        assertEquals(Boolean.TRUE, off.get("applied"));
        assertEquals("off", ov.urrMode());
    }

    @Test
    void putUrrPollMsValidationAndOverrideOnly() {
        EpcConfigResource.RuntimeOverrides ov = new EpcConfigResource.RuntimeOverrides();
        String k = EpcConfigResource.KEY_UP_URR_POLL_MS;
        assertStatus(400, () -> EpcConfigResource.applyUrrPollMs(k, "99", ov));
        assertStatus(400, () -> EpcConfigResource.applyUrrPollMs(k, "xyz", ov));

        // UrrPoller cadence is boot-time final: validated + stored, applied=false.
        Map<String, Object> r = EpcConfigResource.applyUrrPollMs(k, "60000", ov);
        assertEquals(60_000L, r.get("value"));
        assertEquals(Boolean.FALSE, r.get("applied"));
        assertEquals(60_000L, ov.urrPollMs());
        assertNotNull(r.get("note"));
    }

    @Test
    void putRejectsUnknownOrBootTimeOnlyKeys() {
        EpcConfigResource res = newResource();
        assertStatus(404, () -> res.put("epc.cluster.name", "DIST_SYNC"));
        assertStatus(404, () -> res.put("nope", "1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void putViaResourceStoresOverrideAndGetReflectsIt() {
        EpcConfigResource res = newResource(); // runtime == null → override path
        Map<String, Object> r = res.put(EpcConfigResource.KEY_UP_URR_POLL_MS, "\"1234\"");
        assertEquals(EpcConfigResource.KEY_UP_URR_POLL_MS, r.get("key"));
        assertEquals(1234L, r.get("value"));
        assertEquals(Boolean.FALSE, r.get("applied"));

        Map<String, Object> cfg = res.config();
        Map<String, Object> poll = entry(cfg, "upUrrPollMs");
        assertEquals(1234L, poll.get("value"));
        assertEquals(Boolean.TRUE, poll.get("writable"));
        assertEquals(Boolean.TRUE, entry(cfg, "upUrrMode").get("writable"));
        assertEquals(Boolean.TRUE, entry(cfg, "pcrfUsageMonitorThreshold").get("writable"));
        // Boot-time-only knobs stay read-only.
        assertEquals(Boolean.FALSE, entry(cfg, "clusterMode").get("writable"));
    }

    @Test
    void getReflectsThresholdOverrideWhenServiceUnreachable() {
        EpcConfigResource res = newResource();
        res.put(EpcConfigResource.KEY_USAGE_MONITOR_THRESHOLD, "777");
        assertEquals(777L, entry(res.config(), "pcrfUsageMonitorThreshold").get("value"));
    }

    /** Bare resource instance (no CDI): every field set, runtime left null. */
    private static EpcConfigResource newResource() {
        EpcConfigResource r = new EpcConfigResource();
        r.httpPort = 8099;
        r.datastoreEnabled = true;
        r.roleHss = true;
        r.roleSgwc = false;
        r.rolePgwc = false;
        r.rolePcrf = true;
        r.rolePcef = false;
        r.roleUp = false;
        r.diameterPeerHost = Optional.empty();
        r.diameterPeerPort = 3868;
        r.diameterPeerRole = "client";
        r.diameterDestHost = Optional.empty();
        r.diameterDestRealm = Optional.empty();
        r.diameterOriginHost = Optional.empty();
        r.diameterOriginRealm = "epc.lab";
        r.diameterTransport = "tcp";
        r.pcrfDefaultQos = "MBB";
        r.pcrfRxEnabled = true;
        r.pcefCdrEnabled = true;
        r.hssMmeAllowlistEnabled = false;
        r.hssMmeAllowlist = Optional.empty();
        r.upQerEnabled = false;
        r.upUrrMode = "poll";
        r.upUrrPollMs = 60000L;
        r.upUrrBaselinePersistMs = 300000L;
        r.pcefOcsEnabled = false;
        r.sgwuPeerHost = "127.0.0.1";
        r.sgwuPeerPort = 8805;
        r.pgwuPeerHost = "127.0.0.1";
        r.pgwuPeerPort = 8805;
        r.apnAmbrUlBps = 1_000_000_000L;
        r.apnAmbrDlBps = 1_000_000_000L;
        r.ueAmbrUlBps = 1_000_000_000L;
        r.ueAmbrDlBps = 1_000_000_000L;
        r.pdnPoolIpv4 = "10.64.0.0/16";
        r.pdnPoolIpv6 = "2001:db8::/64";
        r.clusterMode = "LOCAL";
        r.clusterName = "epc-cluster";
        r.nodeId = "epc-1";
        r.nodeIndex = 1;
        r.gtpPeerHost = "127.0.0.1";
        r.gtpPeerPort = 2123;
        r.gtpBindPort = 2123;
        r.pfcpPeerHost = "127.0.0.1";
        r.pfcpPeerPort = 8805;
        r.pfcpBindPort = 8805;
        r.s5cWire = false;
        r.s5cPgwcPeerHost = "127.0.0.1";
        r.s5cPgwcPeerPort = 2123;
        r.jdbcUrl = EpcJdbc.DEFAULT_URL;
        r.hssSecretKekHex = "000102030405060708090a0b0c0d0e0f";
        r.ttlSeconds = 7200L;
        return r;
    }

    private static WebApplicationException assertStatus(int status, Executable ex) {
        WebApplicationException e = assertThrows(WebApplicationException.class, ex);
        assertEquals(status, e.getResponse().getStatus());
        return e;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entry(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }
}