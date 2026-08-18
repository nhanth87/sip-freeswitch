package et.elisa.ims.digest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.security.SipDigestHasher;
import org.junit.jupiter.api.Test;

class SipDigestAuthTest {

    @Test
    void verifyMd5WithoutQop() {
        String ha1 = SipDigestHasher.a1Hash("alice", "ims.lab", "alice");
        String nonce = "aabbccddeeff00112233445566778899";
        String uri = "sip:ims.lab";
        String ha2 = SipDigestAuth.md5Hex("REGISTER:" + uri);
        String response = SipDigestAuth.md5Hex(ha1 + ":" + nonce + ":" + ha2);
        String auth = "Digest username=\"alice\", realm=\"ims.lab\", nonce=\"" + nonce
                + "\", uri=\"" + uri + "\", response=\"" + response + "\", algorithm=MD5";
        assertTrue(SipDigestAuth.verify(auth, ha1, "REGISTER", nonce));
        assertFalse(SipDigestAuth.isAka(auth));
        assertTrue(SipDigestAuth.isMd5Digest(auth));
    }

    @Test
    void verifyQopWhenCnoncePrecedesNonce() {
        String ha1 = SipDigestHasher.a1Hash("alice", "ims.lab", "alice");
        String nonce = "c59ea0bafd0e7e5c087aa607ec7f9db3";
        String uri = "sip:127.0.0.1:5060";
        String cnonce = "cadfba6";
        String nc = "00000001";
        String ha2 = SipDigestAuth.md5Hex("REGISTER:" + uri);
        String response = SipDigestAuth.md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        String auth = "Digest username=\"alice\",realm=\"ims.lab\",cnonce=\"" + cnonce
                + "\",nc=" + nc + ",qop=auth,uri=\"" + uri + "\",nonce=\"" + nonce
                + "\",response=\"" + response + "\",algorithm=MD5";
        assertEquals(nonce, SipDigestAuth.nonceOf(auth));
        assertTrue(SipDigestAuth.verify(auth, ha1, "REGISTER", nonce));
    }

    @Test
    void wwwAuthenticateIsMd5NotAka() {
        String www = SipDigestAuth.wwwAuthenticate("ims.lab", "n1");
        assertTrue(www.contains("algorithm=MD5"));
        assertFalse(www.contains("AKAv1"));
        assertEquals("n1", SipDigestAuth.nonceOf(www));
    }
}
