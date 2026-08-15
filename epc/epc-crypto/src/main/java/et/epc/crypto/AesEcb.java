package et.epc.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class AesEcb {
    private AesEcb() {}

    static byte[] encrypt(byte[] key16, byte[] block16) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));
            return c.doFinal(block16);
        } catch (Exception e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }
}
