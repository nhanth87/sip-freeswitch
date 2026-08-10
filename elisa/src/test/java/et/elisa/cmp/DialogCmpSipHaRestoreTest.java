package et.elisa.cmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.sipservlet.SipRaConfig;
import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;
import com.microjainslee.ra.sipservlet.collab.DialogRegistry;
import com.microjainslee.ra.sipservlet.collab.NettySipOutboundSender;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.transport.SipTransport;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.SipDialogHa;
import et.elisa.recovery.DialogOwnerStore;
import et.elisa.recovery.SbbRecoveryManagerImpl;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Lab N–N: DialogCheckpoint SipDialogHa → RA restorePortable → SendResponse.
 */
class DialogCmpSipHaRestoreTest {

    @Test
    void enrichSaveThenRestoreOntoPeerNodeRa() {
        ConcurrentHashMap<String, DialogCheckpoint> shared = new ConcurrentHashMap<>();
        DialogCheckpointStore hot = new DialogCheckpointStore().shareMemory(shared);
        DialogCmpPersistence cmp = new DialogCmpPersistence(hot).withoutPg();

        DialogRegistry live = new DialogRegistry();
        live.restorePortable(new DialogRegistry.PortableDialogMeta(
                "cid-ha", "10.0.0.9", 5060, "UDP",
                "10.0.0.2", 5060, "UDP", 1L,
                "sip:alice@lab", "ua-from", "sip:bob@lab", "scscf-to",
                "sip:alice@10.0.0.9:5060", "sip:1000@10.0.0.2:5060",
                "INVITE", "sip:alice@lab", "far-uac"), () -> "cid-ha");
        cmp.setSipHaEnricher(live::exportPortable);

        long now = Instant.now().toEpochMilli();
        cmp.save(new InviteCmpSnapshot(
                "cid-ha", null, "251911",
                "sip:alice@lab", "sip:bob@lab", "sip:1000@10.0.0.2:5060",
                null, "fs-1", "elisa-1",
                DialogCheckpoint.STATE_CONFIRMED, now, now));

        InviteCmpSnapshot loaded = cmp.load("cid-ha").orElseThrow();
        assertFalse(loaded.effectiveSipHa().isEmpty());
        assertEquals("ua-from", loaded.effectiveSipHa().fromTag());
        assertEquals("10.0.0.9:5060", loaded.peer());

        SipServletResourceAdaptor peerRa = new SipServletResourceAdaptor();
        DialogOwnerStore owners = new DialogOwnerStore().memoryOnly("elisa-2");
        SbbRecoveryManagerImpl mgr = new SbbRecoveryManagerImpl(
                null, cmp, null, owners, hot, null, null);
        mgr.bindSipRa(peerRa);

        assertTrue(mgr.restoreDialogFromCheckpoint("cid-ha"));
        assertTrue(peerRa.hasDialog("cid-ha"));
        assertNotNull(peerRa.dialogRegistry().find("cid-ha").lastRequest());

        CapturingTransport transport = new CapturingTransport();
        Map<String, SipTransport> transports = new HashMap<>();
        transports.put("UDP", transport);
        SipRaConfig config = new SipRaConfig();
        config.setHost("127.0.0.1");
        config.setUdpPort(5060);
        NettySipOutboundSender sender = new NettySipOutboundSender(
                config, peerRa.dialogRegistry(), transports);
        sender.send(new SendResponse("cid-ha", 200, "OK"));
        assertEquals(1, transport.sent.size());
        assertTrue(transport.sent.get(0).startsWith("SIP/2.0 200"));
        assertEquals(new InetSocketAddress("10.0.0.9", 5060), transport.targets.get(0));
    }

    @Test
    void sipDialogHaRoundTripAttrs() {
        SipDialogHa ha = new SipDialogHa(
                "10.0.0.2:5060", "UDP", "UDP", 3L,
                "ft", "tt", "sip:a@x", "sip:b@y", "INVITE",
                "sip:a@x", "far");
        var portable = ha.toPortable("c1", "10.0.0.9:5060", "sip:a@x", "sip:b@y");
        assertTrue(portable.hasWireEssentials());
        assertTrue(portable.hasFarWireEssentials());
        SipDialogHa back = SipDialogHa.fromPortable(portable);
        assertEquals("10.0.0.2:5060", back.remotePeer());
        assertEquals("ft", back.fromTag());
        assertEquals("far", back.farFromTag());
    }

    private static final class CapturingTransport implements SipTransport {
        final List<String> sent = new ArrayList<>();
        final List<InetSocketAddress> targets = new ArrayList<>();

        @Override public void start() { }
        @Override public void stop() { }
        @Override public String protocol() { return "UDP"; }

        @Override
        public boolean send(byte[] data, InetSocketAddress target) {
            sent.add(new String(data, StandardCharsets.UTF_8));
            targets.add(target);
            return true;
        }
    }
}
