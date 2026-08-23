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
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class SgwControlMoreTest {

    private static SgwControl newSgw(HeapSessionStore store, PfcpRaEndpoint pfcp) throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-1");
        InetAddress local = InetAddress.getByName("192.0.2.30");
        InetSocketAddress up = new InetSocketAddress("192.0.2.40", 8805);
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp, local, up);
        return new SgwControl(cfg, store, pgw, pfcp, local, local, up);
    }

    private static Fteid enb(int teid) throws Exception {
        return new Fteid(teid, InetAddress.getByName("192.0.2.50"), 0);
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

    @Test
    void twoImsisGetDistinctUeIps() throws Exception {
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        SgwControl sgw = newSgw(store, pfcp);
        var a = sgw.createSession(new Imsi("001010000000001"), ApnConfig.internet(), enb(0x1111));
        var b = sgw.createSession(new Imsi("001010000000002"), ApnConfig.internet(), enb(0x2222));
        assertNotNull(a.ue().ipv4());
        assertNotNull(b.ue().ipv4());
        assertTrue(!a.ue().ipv4().getHostAddress().equals(b.ue().ipv4().getHostAddress()));
        // one sgw + one pgw checkpoint per session.
        assertEquals(4, store.size());
    }

    @Test
    void modifyAccessByTeidProgramsNewRules() throws Exception {
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        SgwControl sgw = newSgw(store, pfcp);
        var r = sgw.createSession(new Imsi("001010000000001"), ApnConfig.internet(), enb(0x1111));
        Fteid s1u = sgw.modifyAccessByTeid(r.sgwS11c().teid(), enb(0x3333));
        assertNotNull(s1u);
    }

    @Test
    void modifyAccessByTeidUnknownThrows() throws Exception {
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        SgwControl sgw = newSgw(store, pfcp);
        try {
            sgw.modifyAccessByTeid(0xdead, enb(0x1111));
            assertTrue(false, "expected NoSuchElementException");
        } catch (java.util.NoSuchElementException expected) {
            assertTrue(true);
        }
    }

    @Test
    void releaseAccessUnknownTeidReturnsFalse() throws Exception {
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        SgwControl sgw = newSgw(store, pfcp);
        assertFalse(sgw.releaseAccessByTeid(0xdead));
    }

    @Test
    void sameImsiSameApnReusesUeIp() throws Exception {
        HeapSessionStore store = new HeapSessionStore();
        PfcpRaEndpoint pfcp = new PfcpRaEndpoint();
        pfcp.activate(noop());
        SgwControl sgw = newSgw(store, pfcp);
        Imsi imsi = new Imsi("001010000000001");
        var a = sgw.createSession(imsi, ApnConfig.internet(), enb(0x1111));
        var b = sgw.createSession(imsi, ApnConfig.internet(), enb(0x2222));
        assertEquals(a.ue().ipv4().getHostAddress(), b.ue().ipv4().getHostAddress());
    }
}