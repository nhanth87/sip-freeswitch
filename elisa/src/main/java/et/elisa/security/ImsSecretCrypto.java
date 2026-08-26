package et.elisa.security;

import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM wrap for IMS auth material (Ki/OPc, exactly 16 bytes).
 * Blob = hex(nonce[12] || ciphertext || tag[16]); wrong KEK fails closed
 * (AEAD tag mismatch). AAD binds domain {@code ims-secret:v1}.
 * Production supersedes the KEK with HSM; interface unchanged.
 */
public final class ImsSecretCrypto {

    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD = "ims-secret:v1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private ImsSecretCrypto() {
    }

    public static byte[] kekFromHex(String kekHex) {
        if (kekHex == null || kekHex.isBlank()) {
            throw new IllegalArgumentException("KEK required (sf.ims.secret.kek-hex)");
        }
        byte[] kek = HexFormat.of().parseHex(kekHex.trim().replace(" ", ""));
        if (kek.length != 16) {
            throw new IllegalArgumentException("KEK must be 16 bytes (32 hex chars)");
        }
        return kek;
    }

    public static String wrap(byte[] kek16, byte[] block16) {
        requireBlock(block16);
        try {
            byte[] nonce = new byte[NONCE_LEN];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek16, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] ct = cipher.doFinal(block16);
            byte[] blob = new byte[NONCE_LEN + ct.length];
            System.arraycopy(nonce, 0, blob, 0, NONCE_LEN);
            System.arraycopy(ct, 0, blob, NONCE_LEN, ct.length);
            return HexFormat.of().formatHex(blob);
        } catch (Exception e) {
            throw new IllegalStateException("ims secret wrap failed", e);
        }
    }

    public static byte[] unwrap(byte[] kek16, String encHex) {
        if (encHex == null || encHex.isBlank()) {
            throw new IllegalArgumentException("empty secret blob");
        }
        byte[] blob = HexFormat.of().parseHex(encHex.trim());
        if (blob.length <= NONCE_LEN + 16) {
            throw new IllegalStateException("ims secret blob truncated");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek16, "AES"),
                    new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_LEN));
            cipher.updateAAD(AAD);
            return cipher.doFinal(blob, NONCE_LEN, blob.length - NONCE_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("ims secret unwrap failed (wrong KEK?)", e);
        }
    }

    /** Parse a hex key material string and enforce 16 bytes (32 hex chars). */
    public static byte[] parseKeyHex(String hex, String name) {
        if (hex == null || hex.isBlank()) {
            throw new IllegalArgumentException(name + " required");
        }
        byte[] v = HexFormat.of().parseHex(hex.trim());
        if (v.length != 16) {
            throw new IllegalArgumentException(name + " must be 16 bytes (32 hex chars)");
        }
        return v;
    }

    private static void requireBlock(byte[] v) {
        if (v == null || v.length != 16) {
            throw new IllegalArgumentException("secret must be 16 bytes");
        }
    }
}
