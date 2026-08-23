package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.cluster.EpcClusterConfig;
import com.microjainslee.ra.gtpv2c.Gtpv2RaEndpoint;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class EpcBootstrapTest {

    @Test
    void hssWiresVendorDiameterNeverSipServlet() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(true, false, false, false, false, false), EpcClusterConfig.local("epc-hss-1"));
        b.start(EpcBootstrap.loggingBootstrap());
        assertTrue(b.wired().contains(EpcBootstrap.DIAMETER_RA));
        assertFalse(b.wired().contains(EpcBootstrap.SIP_SERVLET_RA));
        assertFalse(b.wired().contains(Gtpv2RaEndpoint.RA_NAME));
        assertFalse(b.s6a().snapshot().live());
        assertTrue(b.s6a().snapshot().localListen());
        b.s6a().ceaAccepted("mme.host.mno");
        assertTrue(b.s6a().snapshot().live());
        assertTrue(b.wired().contains("pcrf"));
        assertNotNull(b.pcrfService());
        b.stop();
    }

    @Test
    void sgwcWiresGtpAndSxaNotSip() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(false, true, false, false, false, false), EpcClusterConfig.local("epc-sgwc-1"));
        b.start(EpcBootstrap.loggingBootstrap());
        assertTrue(b.wired().contains(Gtpv2RaEndpoint.RA_NAME));
        assertTrue(b.wired().contains(PfcpRaEndpoint.RA_NAME));
        assertFalse(b.wired().contains("pcef"));
        assertFalse(b.wired().contains(EpcBootstrap.SIP_SERVLET_RA));
        assertTrue(b.gtp().listening());
        assertFalse(b.gtp().peer(new InetSocketAddress("127.0.0.1", 2123)).live());
        b.stop();
    }

    @Test
    void pgwcWiresDiameterForGxAndPcef() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(false, false, true, false, false, false), EpcClusterConfig.local("epc-pgwc-1"));
        b.start(EpcBootstrap.loggingBootstrap());
        assertTrue(b.wired().contains("pcef"));
        assertTrue(b.wired().contains(PfcpRaEndpoint.RA_NAME));
        assertTrue(b.wired().contains(EpcBootstrap.DIAMETER_RA));
        assertFalse(b.wired().contains(EpcBootstrap.SIP_SERVLET_RA));
        b.stop();
    }

    @Test
    void atLeastOneRoleRequired() {
        assertThrows(IllegalArgumentException.class, () -> new EpcRoles(false, false, false, false, false, false));
    }

    @Test
    void clusterNameIsEpcNotElisa() {
        assertEquals("epc-cluster", EpcClusterConfig.local("n").clusterName());
    }

    @Test
    void jdbcUrlMustBePostgresEpcNotH2() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(false, true, false, false, false, false), EpcClusterConfig.local("epc-sgwc-1"));
        b.withJdbcUrl(EpcJdbc.DEFAULT_URL);
        assertThrows(IllegalStateException.class, () -> b.withJdbcUrl("jdbc:h2:mem:epc"));
    }

    @Test
    void upRoleWiresAgentUrrPollerAndPfcpRa() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(false, false, false, false, false, true), EpcClusterConfig.local("epc-up-1"));
        b.withUp("poll", 1000, 5000, true, new InetSocketAddress("192.0.2.20", 8805));
        b.start(EpcBootstrap.loggingBootstrap());
        assertTrue(b.wired().contains("up"));
        assertTrue(b.wired().contains("up-urr-heap"), "no datasource -> heap URR baseline store");
        assertTrue(b.wired().contains(PfcpRaEndpoint.RA_NAME), "UP speaks N4 via pfcp-ra");
        assertNotNull(b.upAgent());
        assertTrue(b.upAgent().qerEnabled());
        assertNotNull(b.urrIpIndex());
        assertNotNull(b.urrPoller());
        assertTrue(b.urrPoller().running());
        b.stop();
        assertNull(b.urrPoller(), "poller stopped on shutdown");
    }

    @Test
    void upRoleModeOffSkipsPoller() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(false, false, false, false, false, true), EpcClusterConfig.local("epc-up-2"));
        b.withUp("off", 1000, 5000, false, new InetSocketAddress("192.0.2.20", 8805));
        b.start(EpcBootstrap.loggingBootstrap());
        assertNotNull(b.upAgent());
        assertFalse(b.upAgent().qerEnabled());
        assertNull(b.urrPoller(), "mode=off -> no charging loop");
        b.stop();
    }
}
