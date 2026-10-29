package et.elisa.ims.aka;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.digest.SipDigestAuth;
import et.elisa.ims.support.UeAka;
import et.epc.crypto.Sqn;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** IMS AKA (TS 33.203) over Digest AKAv1-MD5 (RFC 3310), checked from the UE side. */
class AkaMd5Rfc3310Test {

    static final byte[] KI = AkaMd5.parseHex("465b5ce8b199b49faa5f0a2ee238a6bc");
    static final byte[] OPC = AkaMd5.parseHex("cd63cb71954a9f4e48a5994e37a02baf");
    static final byte[] AMF = AkaMd5.parseHex("8000");
    static final String USER = "001010000000001@ims.lab";
    static final String REALM = "ims.lab";
    static final String URI = "sip:ims.lab";

    @Test
    void nonceIsBase64RandAutnAndUsimAcceptsTheAutn() {
        long sqn = Sqn.next(Sqn.of(41, 3));
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, sqn);
        String www = AkaMd5.wwwAuthenticate(REALM, v.rand(), v.autn());
        String nonce = SipDigestAuth.param(www, "nonce");

        assertEquals(32, Base64.getDecoder().decode(nonce).length, "RFC 3310 §3.2 base64(RAND ‖ AUTN)");
        assertTrue(www.contains("algorithm=AKAv1-MD5"));
        UeAka.Challenge c = UeAka.accept(KI, OPC, nonce);
        assertTrue(c.macOk(), "USIM verifies MAC-A");
        assertEquals(sqn, c.sqn(), "USIM recovers SQN = (SQN ⊕ AK) ⊕ AK");
        assertArrayEquals(v.xres(), c.res());
        assertNotEquals(String.format("%012x", sqn), AkaMd5.toHex(v.autn()).substring(0, 12),
                "SQN never travels in clear (TS 33.102 §6.3.2)");
    }

    @Test
    void responseIsADigestWithResAsPassword() {
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, Sqn.next(0));
        String nonce = AkaMd5.nonce(v.rand(), v.autn());
        UeAka.Challenge c = UeAka.accept(KI, OPC, nonce);
        String auth = UeAka.authorization(USER, REALM, URI, nonce, c.res());

        assertTrue(AkaMd5.verifyResponse(auth, v.xres(), "REGISTER", nonce));
        assertFalse(AkaMd5.verifyResponse(auth, v.xres(), "REGISTER", AkaMd5.nonce(v.autn(), v.rand())),
                "stale / foreign nonce");
        byte[] wrong = v.xres().clone();
        wrong[0] ^= 1;
        assertFalse(AkaMd5.verifyResponse(auth, wrong, "REGISTER", nonce));
        String clearRes = "Digest username=\"" + USER + "\", realm=\"" + REALM + "\", nonce=\"" + nonce
                + "\", uri=\"" + URI + "\", response=\"" + AkaMd5.toHex(v.xres()) + "\"";
        assertFalse(AkaMd5.verifyResponse(clearRes, v.xres(), "REGISTER", nonce),
                "RES in clear is not an RFC 3310 response");
    }

    @Test
    void autsIsParsedForResync() {
        AkaMd5.Vector v = AkaMd5.generate(KI, OPC, AMF, Sqn.next(0));
        String nonce = AkaMd5.nonce(v.rand(), v.autn());
        String resync = UeAka.resyncAuthorization(USER, REALM, URI, nonce, KI, OPC, Sqn.of(900, 4));

        byte[] auts = AkaMd5.auts(resync);
        assertEquals(14, auts.length);
        assertEquals(Sqn.of(900, 4), et.epc.crypto.Auts.recoverSqnMs(KI, OPC, v.rand(), auts));
        assertArrayEquals(v.rand(), AkaMd5.randFromNonce(nonce));
        assertFalse(AkaMd5.hasResponse(resync));
        assertNull(AkaMd5.auts("Digest response=\"x\""));
    }

    @Test
    void everyVectorHasAFreshRand() {
        assertNotEquals(AkaMd5.toHex(AkaMd5.generate(KI, OPC, AMF, 33).rand()),
                AkaMd5.toHex(AkaMd5.generate(KI, OPC, AMF, 33).rand()));
    }
}
