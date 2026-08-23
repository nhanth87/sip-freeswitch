package et.epc.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public final class AesEcb {
    private AesEcb() {}

    /** AES-ECB NoPadding over a 16-byte block. KEK wrap for HSS secret material. */
    public static byte[] encrypt(byte[] key16, byte[] block16) {
        return crypt(Cipher.ENCRYPT_MODE, key16, block16);
    }

    public static byte[] decrypt(byte[] key16, byte[] block16) {
        return crypt(Cipher.DECRYPT_MODE, key16, block16);
    }

    private static byte[] crypt(int mode, byte[] key16, byte[] block16) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(mode, new SecretKeySpec(key16, "AES"));
            return c.doFinal(block16);
        } catch (Exception e) {
            throw new IllegalStateException("AES unavailable", e);
        }
    }
}
