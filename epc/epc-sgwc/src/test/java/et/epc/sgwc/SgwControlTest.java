package et.epc.sgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.pcef.PcefService;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class SgwControlTest {

    @Test
    void createSessionAllocatesIpAndProgramsPfcp() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress up = new InetSocketAddress("192.0.2.40", 8805);
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, up);
        SgwControl sgw = new SgwControl(cfg, store, pgw, pfcp, local, local, up);
        Imsi imsi = new Imsi("001010000000001");
        Fteid enb = new Fteid(0x1111, InetAddress.getByName("192.0.2.50"), 0);
        SgwCreateSessionResult r = sgw.createSession(imsi, ApnConfig.internet(), enb);
        assertNotNull(r.ue().ipv4());
        assertNotNull(r.ue().ipv6Prefix());
        assertEquals(1, (r.sgwS11c().teid() >>> 24) & 0xff);
        assertTrue(store.size() >= 1);
        assertTrue(pfcp.sessionInstalled(r.pgw().pfcpSeid()));
    }

    @Test
    void imsApnStillGetsDefaultBearerWithoutRxClaim() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress up = new InetSocketAddress("192.0.2.40", 8805);
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, up);
        SgwControl sgw = new SgwControl(cfg, store, pgw, pfcp, local, local, up);
        SgwCreateSessionResult r = sgw.createSession(
                new Imsi("001010000000002"),
                ApnConfig.ims("192.0.2.10"),
                new Fteid(0x2222, InetAddress.getByName("192.0.2.50"), 0));
        assertEquals("ims-qci5", r.pgw().policy().ruleName());
        assertEquals(5, r.pgw().policy().qos().qci());
    }

    @Test
    void releaseAccessByTeidClearsS1uKeepsSession() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-1");
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress up = new InetSocketAddress("192.0.2.40", 8805);
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, up);
        SgwControl sgw = new SgwControl(cfg, store, pgw, pfcp, local, local, up);
        SgwCreateSessionResult r = sgw.createSession(
                new Imsi("001010000000001"),
                ApnConfig.internet(),
                new Fteid(0x1111, InetAddress.getByName("192.0.2.50"), 0));
        assertTrue(sgw.releaseAccessByTeid(r.sgwS11c().teid()));
        assertTrue(store.size() >= 1);
        assertTrue(store.findByS11cTeid(r.sgwS11c().teid()).isPresent());
        assertFalse(sgw.releaseAccessByTeid(0xdead));
    }

    private static RaBootstrapPort noop() {
        return new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
            }
        };
    }
}
