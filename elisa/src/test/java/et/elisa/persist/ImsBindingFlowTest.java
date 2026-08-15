package et.elisa.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImsBindingFlowTest {

    @Test
    void flowNextHop_usesReceivedNotContact() {
        ImsBinding b = new ImsBinding();
        b.impu = "sip:alice@ims.lab";
        b.contact = "sip:alice@172.21.0.1:5060";
        b.receivedHost = "192.0.2.80";
        b.receivedPort = 5060;
        b.receivedTransport = "UDP";
        assertTrue(b.flowDiffersFromContact());
        assertEquals("sip:alice@192.0.2.80:5060;transport=udp", b.flowNextHopUri());
        assertTrue(ImsBinding.looksLikeIpHost(b.contact));
        assertFalse(ImsBinding.looksLikeIpHost("sip:alice@ims.lab"));
    }

    @Test
    void flowNextHop_nullWhenNoReceived() {
        ImsBinding b = new ImsBinding();
        b.contact = "sip:bob@10.0.0.9:5060";
        assertFalse(b.hasReceivedFlow());
        assertFalse(b.flowDiffersFromContact());
        org.junit.jupiter.api.Assertions.assertNull(b.flowNextHopUri());
    }
}
