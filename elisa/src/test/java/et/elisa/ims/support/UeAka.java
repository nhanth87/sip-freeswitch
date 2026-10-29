package et.elisa.ims.support;

import et.elisa.ims.aka.AkaMd5;
import et.elisa.ims.digest.SipDigestAuth;
import et.epc.crypto.Bytes;
import et.epc.crypto.Milenage;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * UE side of IMS AKA, written independently of the server code: what an ISIM/USIM + a
 * RFC 3310 client do with a 401 nonce. Verifies AUTN like a real USIM (TS 33.102 §6.3.3):
 * SQN = (SQN ⊕ AK) ⊕ AK and MAC-A = f1(SQN ‖ RAND ‖ AMF), then answers with the RFC 2617
 * digest using RES (octets) as the password.
 */
public final class UeAka {

    private UeAka() {
    }

    public record Challenge(byte[] rand, byte[] autn, long sqn, byte[] res, boolean macOk) {}

    public static Challenge accept(byte[] ki, byte[] opc, String nonceB64) {
        byte[] raw = Base64.getDecoder().decode(nonceB64);
        byte[] rand = Arrays.copyOf(raw, 16);
        byte[] autn = Arrays.copyOfRange(raw, 16, 32);
        Milenage.Result first = Milenage.compute(ki, opc, rand, new byte[6], Arrays.copyOfRange(autn, 6, 8));
        byte[] sqn6 = Bytes.xor(Arrays.copyOf(autn, 6), first.ak());
        byte[] amf = Arrays.copyOfRange(autn, 6, 8);
        Milenage.Result m = Milenage.compute(ki, opc, rand, sqn6, amf);
        boolean macOk = MessageDigest.isEqual(m.macA(), Arrays.copyOfRange(autn, 8, 16));
        return new Challenge(rand, autn, Bytes.bytesToSqn(sqn6), m.res(), macOk);
    }

    /** RFC 3310 / RFC 2617 Authorization (qop=auth) answering {@code nonce} with {@code res}. */
    public static String authorization(String username, String realm, String uri, String nonce, byte[] res) {
        String nc = "00000001";
        String cnonce = "0a4f113b";
        String ha1 = AkaMd5.ha1(username, realm, res);
        String ha2 = SipDigestAuth.md5Hex("REGISTER:" + uri);
        String response = SipDigestAuth.md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
        return "Digest username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce
                + "\", uri=\"" + uri + "\", response=\"" + response + "\", algorithm=AKAv1-MD5, qop=auth, nc="
                + nc + ", cnonce=\"" + cnonce + "\"";
    }

    /** RFC 3310 §3.4 synchronisation failure: auts=base64(AUTS) for the USIM's SQN_MS. */
    public static String resyncAuthorization(String username, String realm, String uri, String nonce,
                                             byte[] ki, byte[] opc, long sqnMs) {
        byte[] rand = Arrays.copyOf(Base64.getDecoder().decode(nonce), 16);
        byte[] amfStar = new byte[2];
        Milenage.Result first = Milenage.compute(ki, opc, rand, new byte[6], amfStar);
        byte[] sqn6 = Bytes.sqnToBytes(sqnMs);
        Milenage.Result mac = Milenage.compute(ki, opc, rand, sqn6, amfStar);
        byte[] auts = Bytes.concat(Bytes.xor(sqn6, first.aks()), mac.macS());
        return "Digest username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce
                + "\", uri=\"" + uri + "\", response=\"\", algorithm=AKAv1-MD5, auts=\""
                + Base64.getEncoder().encodeToString(auts) + "\"";
    }
}
