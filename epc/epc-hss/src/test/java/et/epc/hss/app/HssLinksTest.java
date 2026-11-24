package et.epc.hss.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.jss7.Ss7RaConfig;
import et.epc.api.PlmnId;
import et.epc.hss.UeauService;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HssLinks}: parsing, defaults, SCTP-only SS7, allowlist, KEK refusal, link-status truth.
 * Binds only to 127.0.0.1 ephemeral ports; never opens an SCTP socket and talks to no real peer.
 */
class HssLinksTest {

    static final String REAL_KEK = "00112233445566778899aabbccddeeff";

    private HssMain.Running running;
    private MicroSleeContainer container;

    @AfterEach
    void tearDown() {
        if (running != null) {
            running.stop();
        }
        if (container != null && container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    static HssLinks.Config load(String... kv) {
        return HssLinks.load(lookup(kv));
    }

    static HssLinks.Lookup lookup(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return HssLinks.Lookup.of(m);
    }

    // ---------------------------------------------------------------- defaults / lookup

    @Test
    void everyLinkIsOffByDefaultWithLoopbackDefaults() {
        HssLinks.Config c = load();
        assertFalse(c.lab());
        assertFalse(c.diameter().enabled());
        assertFalse(c.ss7().enabled());
        assertFalse(c.sbi().enabled());
        assertTrue(c.faces().isEmpty());

        assertEquals("127.0.0.1", c.diameter().host());
        assertEquals(3868, c.diameter().port());
        assertEquals(HssLinks.DiameterTransport.TCP, c.diameter().transport());
        assertEquals("hss.epc.lab", c.diameter().originHost());
        assertEquals("epc.lab", c.diameter().originRealm());

        assertEquals(6, c.ss7().localSsn(), "HLR");
        assertEquals(7, c.ss7().remoteSsn(), "VLR");
        assertEquals("127.0.0.1", c.ss7().hostIp());

        assertEquals("127.0.0.1", c.sbi().host());
        assertEquals(7777, c.sbi().port());
        assertEquals(5_000L, c.sbi().responseTimeoutMs());
        assertTrue(c.sbi().allowedServingPlmns().isEmpty());
    }

    @Test
    void envNamesAndSystemPropertyLookup() {
        assertEquals("HSS_SBI_ALLOWED_SERVING_PLMNS", HssLinks.envName(HssLinks.SBI_ALLOWED_SERVING_PLMNS));
        assertEquals("HSS_SECRET_KEK_HEX", HssLinks.envName(HssLinks.KEK));
        String key = "hss.links-test.only";
        System.setProperty(key, "from-sysprop");
        try {
            assertEquals("from-sysprop", HssLinks.Lookup.system().get(key));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void malformedValuesAreRefusedNotDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.SBI_ENABLED, "yes"));
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.SBI_PORT, "77x7"));
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.SBI_PORT, "70000"));
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.SS7_LOCAL_SSN, "300"));
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.SBI_RESPONSE_TIMEOUT_MS, "-1"));
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.DIAMETER_PEERS, "nohostport"));
    }

    // ---------------------------------------------------------------- Diameter

    @Test
    void diameterConfigMapsOntoTheRaConfig() {
        HssLinks.Config c = load(
                HssLinks.DIAMETER_ENABLED, "true",
                HssLinks.DIAMETER_HOST, "127.0.0.1",
                HssLinks.DIAMETER_PORT, "3870",
                HssLinks.DIAMETER_ORIGIN_HOST, "hss1.epc.mno",
                HssLinks.DIAMETER_ORIGIN_REALM, "epc.mno",
                HssLinks.DIAMETER_TRANSPORT, "SCTP",
                HssLinks.DIAMETER_PEERS, "mme:3868:client:mme.epc.mno:epc.mno:s6a");
        assertEquals(EnumSet.of(HssApp.Face.DIAMETER), c.faces());
        DiameterRaConfig ra = c.diameter().toRaConfig();
        assertEquals("127.0.0.1", ra.host());
        assertEquals(3870, ra.port());
        assertEquals("hss1.epc.mno", ra.originHost());
        assertEquals("epc.mno", ra.realm());
        assertTrue(ra.sctpEnabled());
        assertFalse(ra.tcpEnabled());
        assertEquals(1, ra.peers().size());
        assertEquals("mme", ra.peers().get(0).host());
        assertFalse(ra.peers().get(0).server());

        DiameterRaConfig tcp = load(HssLinks.DIAMETER_TRANSPORT, "tcp").diameter().toRaConfig();
        assertTrue(tcp.tcpEnabled());
        assertFalse(tcp.sctpEnabled());
        assertTrue(tcp.peers().isEmpty());

        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.DIAMETER_TRANSPORT, "udp"));
    }

    // ---------------------------------------------------------------- SS7

    @Test
    void ss7ConfigIsSctpMapOnlyHlrSsn() {
        HssLinks.Config c = load(
                HssLinks.SS7_ENABLED, "true",
                HssLinks.SS7_CHANNEL, " sctp ",
                HssLinks.SS7_HOST_IP, "127.0.0.1",
                HssLinks.SS7_HOST_PORT, "2915",
                HssLinks.SS7_PEER_IP, "127.0.0.2",
                HssLinks.SS7_PEER_PORT, "2916",
                HssLinks.SS7_OPC, "4001",
                HssLinks.SS7_DPC, "4002",
                HssLinks.SS7_ROUTING_CONTEXT, "7",
                HssLinks.SS7_NETWORK_APPEARANCE, "8");
        assertEquals(EnumSet.of(HssApp.Face.SS7), c.faces());
        Ss7RaConfig ra = c.ss7().toRaConfig();
        assertEquals("SCTP", ra.ipChannelType());
        assertTrue(ra.mapEnabled());
        assertFalse(ra.capEnabled());
        assertEquals(6, ra.localSsn());
        assertEquals(7, ra.remoteSsn());
        assertEquals("127.0.0.1", ra.hostIp());
        assertEquals(2915, ra.hostPort());
        assertEquals("127.0.0.2", ra.peerIp());
        assertEquals(2916, ra.peerPort());
        assertEquals(4001, ra.originatingPointCode());
        assertEquals(4002, ra.destinationPointCode());
        assertEquals(7L, ra.routingContext());
        assertEquals(8L, ra.networkAppearance());
    }

    @Test
    void ss7ChannelOtherThanSctpIsRefusedAtConfigTime() {
        for (String bad : List.of("TCP", "tcp", " Tcp ", "", "  ", "udp", "sctp-tcp")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> load(HssLinks.SS7_ENABLED, "true", HssLinks.SS7_CHANNEL, bad), "channel '" + bad + "'");
            assertTrue(e.getMessage().contains("SCTP only"), e.getMessage());
        }
        // refused even with the link off: a TCP SS7 value never sits in a config
        assertThrows(IllegalArgumentException.class, () -> load(HssLinks.SS7_CHANNEL, "TCP"));
        // absent = SCTP
        assertEquals("SCTP", load(HssLinks.SS7_ENABLED, "true").ss7().toRaConfig().ipChannelType());
        assertEquals("SCTP", load(HssLinks.SS7_CHANNEL, "SCTP").ss7().toRaConfig().ipChannelType());
    }

    /** RA never activated (container not started) → the link is FAILED, never reported up. */
    @Test
    void ss7LinkThatIsNotActiveFailsLoudly() {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder().build());
        HssLinks.Bound b = HssLinks.bind(container, load(HssLinks.SS7_ENABLED, "true"));
        assertTrue(container.registeredRaCommandPortNames().contains("ra-jss7"));
        assertFalse(b.ss7Active());
        assertTrue(b.ss7Status().startsWith("FAILED("), b.ss7Status());
        IllegalStateException e = assertThrows(IllegalStateException.class, b::verify);
        assertTrue(e.getMessage().contains("ss7"), e.getMessage());
    }

    // ---------------------------------------------------------------- disabled links

    @Test
    void disabledLinksRegisterNoRaAndReportOff() {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder().build());
        HssLinks.Bound b = HssLinks.bind(container, load());
        assertTrue(container.registeredRaCommandPortNames().isEmpty());
        assertEquals("diameter=off ss7=off sbi=off", b.statusLine());
        b.verify();
        assertEquals(-1, b.sbiPort());
    }

    // ---------------------------------------------------------------- allowlist

    @Test
    void allowedServingPlmnsParse() {
        assertEquals(List.of(new PlmnId("001", "01"), new PlmnId("452", "04")),
                load(HssLinks.SBI_ALLOWED_SERVING_PLMNS, "00101,45204").sbi().allowedServingPlmns());
        assertEquals(List.of(new PlmnId("310", "410"), new PlmnId("001", "001")),
                HssLinks.parsePlmns(" 310410 , 001-001, 310410,"));
        assertTrue(HssLinks.parsePlmns(null).isEmpty());
        assertTrue(HssLinks.parsePlmns("  ").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> HssLinks.parsePlmns("0010"));
        assertThrows(IllegalArgumentException.class, () -> HssLinks.parsePlmns("0010101"));
        assertThrows(IllegalArgumentException.class, () -> HssLinks.parsePlmns("00a01"));
        assertThrows(IllegalArgumentException.class, () -> HssLinks.parsePlmns("01-01"));
    }

    // ---------------------------------------------------------------- KEK

    @Test
    void defaultOrBlankKekIsRefusedOutsideLab() {
        for (String kek : new String[] {null, "", "  ", HssBootstrap.DEFAULT_KEK_HEX,
                HssBootstrap.DEFAULT_KEK_HEX.toUpperCase()}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> HssLinks.resolveKek(kek, false));
            assertTrue(e.getMessage().contains("refusing to start"), e.getMessage());
            assertFalse(e.getMessage().contains(HssBootstrap.DEFAULT_KEK_HEX), "KEK value never in messages");
        }
        assertThrows(IllegalStateException.class, () -> load().kek());
    }

    @Test
    void labAcceptsTheDefaultKekAndARealKekIsAlwaysAccepted() {
        assertEquals(HssBootstrap.DEFAULT_KEK_HEX, HssLinks.resolveKek(null, true));
        assertEquals(HssBootstrap.DEFAULT_KEK_HEX, load(HssLinks.LAB, "true").kek());
        assertEquals(REAL_KEK, HssLinks.resolveKek(REAL_KEK, false));
        HssLinks.Config c = load(HssLinks.KEK, REAL_KEK);
        assertEquals(REAL_KEK, c.kek());
        assertFalse(c.toString().contains(REAL_KEK), "Config.toString redacts the KEK");
    }

    @Test
    void hssMainRefusesTheDefaultKekBeforeBindingAnything() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> HssMain.boot(lookup(HssLinks.SBI_ENABLED, "true", HssLinks.SBI_PORT, "0")));
        assertTrue(e.getMessage().contains("refusing to start"), e.getMessage());
    }

    // ---------------------------------------------------------------- live boots (127.0.0.1 only)

    @Test
    void labBootWithEveryLinkOffStartsAndReportsOff() {
        running = HssMain.boot(lookup(HssLinks.LAB, "true"));
        assertEquals("diameter=off ss7=off sbi=off", running.links().statusLine());
        assertTrue(running.app().wired().isEmpty());
    }

    /** HssMain wires SBI → UdmUeauSbb → UeauService end to end; status is the real bound port. */
    @Test
    void sbiLinkListensOnLoopbackAndServesNudmUeau() throws Exception {
        running = HssMain.boot(lookup(
                HssLinks.KEK, REAL_KEK,
                HssLinks.SBI_ENABLED, "true",
                HssLinks.SBI_HOST, "127.0.0.1",
                HssLinks.SBI_PORT, "0",
                HssLinks.SBI_ALLOWED_SERVING_PLMNS, "45204"));
        int port = running.links().sbiPort();
        assertTrue(port > 0);
        assertEquals("diameter=off ss7=off sbi=listening h2c 127.0.0.1:" + port, running.links().statusLine());
        assertEquals(List.of("SbbType:UdmUeauSbb", "EventMapping:HttpWebRequestEvent->UdmUeauSbb"),
                running.app().wired());

        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> rsp = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/nudm-ueau/v1/imsi-001019999999999/security-information/generate-auth-data"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(HssAppTest.UEAU_BODY)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, rsp.statusCode());
        assertTrue(rsp.body().contains(UeauService.USER_NOT_FOUND), rsp.body());
        client.close();
    }

    /** An enabled link that cannot bind is a failed start, never a link reported up. */
    @Test
    void sbiPortInUseFailsTheStart() throws Exception {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> running = HssMain.boot(lookup(
                    HssLinks.LAB, "true",
                    HssLinks.SBI_ENABLED, "true",
                    HssLinks.SBI_HOST, "127.0.0.1",
                    HssLinks.SBI_PORT, Integer.toString(taken.getLocalPort()))));
            assertTrue(e.getMessage().contains("sbi FAILED("), e.getMessage());
        }
    }

    @Test
    void diameterTcpLinkBindsOnLoopbackAndClaimsNoPeer() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        running = HssMain.boot(lookup(
                HssLinks.LAB, "true",
                HssLinks.DIAMETER_ENABLED, "true",
                HssLinks.DIAMETER_HOST, "127.0.0.1",
                HssLinks.DIAMETER_PORT, Integer.toString(port)));
        assertEquals("diameter=bound(tcp 127.0.0.1:" + port + ", peerReady=false) ss7=off sbi=off",
                running.links().statusLine());
        assertEquals(List.of("SbbType:HssSbb", "EventMapping:DiameterRequestEvent->HssSbb"), running.app().wired());
    }
}
