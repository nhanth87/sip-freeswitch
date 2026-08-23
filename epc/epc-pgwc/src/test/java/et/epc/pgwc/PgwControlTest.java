package et.epc.pgwc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.api.cluster.SessionCheckpoint;
import et.epc.pcef.Cdr;
import et.epc.pcef.PcefService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PgwControlTest {

    private static final Imsi IMSI = new Imsi("001010000000001");

    private static final class RecordingPort implements RaCommandPort {
        final List<OutboundCommand> cmds = new ArrayList<>();

        @Override
        public void sendCommand(OutboundCommand command) {
            cmds.add(command);
        }
    }

    private static Fteid sgwS5u() throws Exception {
        return new Fteid(0x2222, InetAddress.getByName("192.0.2.50"), 4);
    }

    @Test
    void createSessionProgramsPfcpAndCheckpointsPgw() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        HeapSessionStore store = new HeapSessionStore();
        RecordingPort pfcp = new RecordingPort();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store, pfcp,
                InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        CreateSessionResult r = pgw.createSession(IMSI, ApnConfig.internet(), sgwS5u());
        assertNotNull(r.ue().ipv4());
        assertNotNull(r.pgwS5c());
        assertNotNull(r.pgwS5u());
        assertEquals(1, r.chargingId().value());
        assertTrue(r.pfcpSeid() != 0);
        assertEquals(1, pfcp.cmds.size());
        assertTrue(pfcp.cmds.get(0) instanceof PfcpProgramSession);
        PfcpProgramSession p = (PfcpProgramSession) pfcp.cmds.get(0);
        assertEquals(2, p.rules().size());
        assertEquals(1, store.size());
        SessionCheckpoint cp = store.get(SessionCheckpoint.pgwId(
                new et.epc.api.SessionKeys(IMSI, "internet", new et.epc.api.BearerId(5)))).orElseThrow();
        assertEquals("pgw", cp.role());
        assertNotNull(cp.pgwS5u());
        assertEquals(sgwS5u().key(), cp.sgwS5u().key());
    }

    @Test
    void deleteSessionReleasesIpChargingAndCheckpoint() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        HeapSessionStore store = new HeapSessionStore();
        RecordingPort pfcp = new RecordingPort();
        PcefService pcef = new PcefService();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), pcef, store, pfcp,
                InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        pgw.createSession(IMSI, ApnConfig.internet(), sgwS5u());
        assertEquals(1, store.size());
        pgw.deleteSession(IMSI, "internet");
        assertEquals(0, store.size());
        assertEquals(1, pcef.cdrs().size());
    }

    @Test
    void deleteUnknownSessionIsNoop() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        HeapSessionStore store = new HeapSessionStore();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), store,
                new RecordingPort(), InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        pgw.deleteSession(IMSI, "internet");
        assertEquals(0, store.size());
    }

    @Test
    void seidMonotonicAcrossSessions() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        RecordingPort pfcp = new RecordingPort();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), new PcefService(), new HeapSessionStore(),
                pfcp, InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        var a = pgw.createSession(IMSI, ApnConfig.internet(), sgwS5u());
        var b = pgw.createSession(new Imsi("001010000000002"), ApnConfig.internet(), sgwS5u());
        assertTrue(b.pfcpSeid() > a.pfcpSeid());
        assertTrue(b.chargingId().value() > a.chargingId().value());
    }

    @Test
    void reportUsageMapsSeidToChargingSession() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        HeapSessionStore store = new HeapSessionStore();
        PcefService pcef = new PcefService();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), pcef, store, new RecordingPort(),
                InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        CreateSessionResult r = pgw.createSession(IMSI, ApnConfig.internet(), sgwS5u());

        assertTrue(pgw.reportUsage(r.pfcpSeid(), List.of(
                new PfcpUsageReport(1, PfcpIe.UT_VOLTH, 100, 250, 3),
                new PfcpUsageReport(2, PfcpIe.UT_PERIO, 40, 60, 2))));

        pgw.deleteSession(IMSI, "internet");
        Cdr cdr = pcef.cdrs().get(0);
        assertEquals(r.chargingId(), cdr.chargingId());
        assertEquals(140, cdr.uplinkOctets());
        assertEquals(310, cdr.downlinkOctets());
    }

    @Test
    void reportUsageUnknownSeidIsIgnored() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        PcefService pcef = new PcefService();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), pcef, new HeapSessionStore(),
                new RecordingPort(), InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));

        assertFalse(pgw.reportUsage(0xdeadbeefL, List.of(
                new PfcpUsageReport(1, PfcpIe.UT_PERIO, 10, 10, 1))));
        assertTrue(pcef.cdrs().isEmpty());
    }

    @Test
    void reportUsageAfterDeleteIsIgnored() throws Exception {
        EpcClusterConfig cfg = EpcClusterConfig.local("epc-pgw-1");
        HeapSessionStore store = new HeapSessionStore();
        PcefService pcef = new PcefService();
        PgwControl pgw = new PgwControl(cfg, new Ipam(), pcef, store, new RecordingPort(),
                InetAddress.getByName("192.0.2.30"),
                new InetSocketAddress("192.0.2.40", 8805));
        CreateSessionResult r = pgw.createSession(IMSI, ApnConfig.internet(), sgwS5u());
        pgw.deleteSession(IMSI, "internet");

        assertFalse(pgw.reportUsage(r.pfcpSeid(), List.of(
                new PfcpUsageReport(1, PfcpIe.UT_PERIO, 10, 10, 1))));
        assertEquals(1, pcef.cdrs().size());
    }
}