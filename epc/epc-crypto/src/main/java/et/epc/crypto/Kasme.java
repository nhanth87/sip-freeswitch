package et.epc.crypto;

import et.epc.api.PlmnId;
import java.nio.ByteBuffer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TS 33.401 Annex A.2 — KASME = KDF(CK||IK, FC=0x10, SN id, SQN⊕AK).
 * KDF is HMAC-SHA-256 per TS 33.220.
 */
public final class Kasme {

    private Kasme() {}

    public static byte[] derive(byte[] ck, byte[] ik, PlmnId plmn, byte[] sqnXorAk) {
        byte[] key = Bytes.concat(ck, ik);
        byte[] sn = plmn.snId();
        byte[] s = ByteBuffer.allocate(1 + sn.length + 2 + sqnXorAk.length + 2)
                .put((byte) 0x10)
                .put(sn)
                .putShort((short) sn.length)
                .put(sqnXorAk)
                .putShort((short) sqnXorAk.length)
                .array();
        return hmacSha256(key, s);
    }

    static byte[] hmacSha256(byte[] key, byte[] s) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
