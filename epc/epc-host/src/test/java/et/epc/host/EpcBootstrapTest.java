package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                new EpcRoles(true, false, false), EpcClusterConfig.local("epc-hss-1"));
        b.start(EpcBootstrap.loggingBootstrap());
        assertTrue(b.wired().contains(EpcBootstrap.DIAMETER_RA));
        assertFalse(b.wired().contains(EpcBootstrap.SIP_SERVLET_RA));
        assertFalse(b.wired().contains(Gtpv2RaEndpoint.RA_NAME));
        assertFalse(b.s6a().snapshot().live());
        assertTrue(b.s6a().snapshot().localListen());
        b.s6a().ceaAccepted("mme.host.mno");
        assertTrue(b.s6a().snapshot().live());
        b.stop();
    }

    @Test
    void sgwcWiresGtpAndSxaNotSip() {
        EpcBootstrap b = new EpcBootstrap(
                new EpcRoles(false, true, false), EpcClusterConfig.local("epc-sgwc-1"));
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
                new EpcRoles(false, false, true), EpcClusterConfig.local("epc-pgwc-1"));
        b.start(EpcBootstrap.loggingBootstrap());
        assertTrue(b.wired().contains("pcef"));
        assertTrue(b.wired().contains(PfcpRaEndpoint.RA_NAME));
        assertTrue(b.wired().contains(EpcBootstrap.DIAMETER_RA));
        assertFalse(b.wired().contains(EpcBootstrap.SIP_SERVLET_RA));
        b.stop();
    }

    @Test
    void atLeastOneRoleRequired() {
        assertThrows(IllegalArgumentException.class, () -> new EpcRoles(false, false, false));
    }

    @Test
    void clusterNameIsEpcNotElisa() {
        assertEquals("epc-cluster", EpcClusterConfig.local("n").clusterName());
    }
}
