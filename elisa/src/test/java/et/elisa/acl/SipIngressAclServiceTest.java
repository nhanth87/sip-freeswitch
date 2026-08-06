package et.elisa.acl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SipIngressAclServiceTest {

    @Test
    void peerFromViaPrefersReceived() {
        String peer = SipIngressAclService.peerFromViaHeaders(List.of(
                "SIP/2.0/UDP 192.168.1.5:5060;branch=z9hG4bK;received=203.0.113.9"));
        assertEquals("203.0.113.9", peer);
    }

    @Test
    void peerFromViaUsesHost() {
        String peer = SipIngressAclService.peerFromViaHeaders(List.of(
                "SIP/2.0/UDP 198.51.100.7:5060;branch=z9hG4bK"));
        assertEquals("198.51.100.7", peer);
    }

    @Test
    void peerFromContact() {
        assertEquals("10.1.2.3",
                SipIngressAclService.peerFromContactUri("sip:alice@10.1.2.3:5060"));
    }

    @Test
    void cidrAllowAndDeny() {
        SipIngressAclService.Rule allow = SipIngressAclService.Rule.parse("allow", "203.0.113.0/24");
        SipIngressAclService.Rule deny = SipIngressAclService.Rule.parse("deny", "203.0.113.50/32");
        assertTrue(allow.matches("203.0.113.10"));
        assertFalse(allow.matches("198.51.100.1"));
        assertTrue(deny.matches("203.0.113.50"));
    }
}
