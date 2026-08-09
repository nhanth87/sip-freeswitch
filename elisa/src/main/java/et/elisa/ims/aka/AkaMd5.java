package et.elisa.ims.aka;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lab IMS AKA (Milenage f1–f5 subset) for REGISTER challenges.
 * Ki/OPc are hex strings (32 hex = 16 bytes).
 */
public final class AkaMd5 {

    private static final SecureRandom RNG = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private AkaMd5() {}

    public record Vector(byte[] rand, byte[] autn, byte[] xres, byte[] ck, byte[] ik, long nextSqn) {}

    public static Vector generate(byte[] ki, byte[] opc, byte[] amf, long sqn) {
        byte[] rand = new byte[16];
        RNG.nextBytes(rand);
        long next = sqn + 1;
        byte[] sqnBytes = sqnToBytes(next);
        byte[] macA = f1(ki, opc, rand, sqnBytes, amf);
        byte[] autn = new byte[16];
        System.arraycopy(sqnBytes, 0, autn, 0, 6);
        System.arraycopy(amf, 0, autn, 6, 2);
        System.arraycopy(macA, 0, autn, 8, 8);
        byte[] res = f2(ki, opc, rand);
        byte[] ck = f3(ki, opc, rand);
        byte[] ik = f4(ki, opc, rand);
        return new Vector(rand, autn, res, ck, ik, next);
    }

    public static boolean verifyRes(byte[] expectedXres, byte[] res) {
        if (expectedXres == null || res == null) {
            return false;
        }
        int n = Math.min(expectedXres.length, res.length);
        if (n == 0) {
            return false;
        }
        return MessageDigest.isEqual(
                Arrays.copyOf(expectedXres, n),
                Arrays.copyOf(res, n));
    }

    public static byte[] parseHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return new byte[16];
        }
        String h = hex.trim().replace(" ", "");
        if (h.length() % 2 != 0) {
            h = "0" + h;
        }
        return HEX.parseHex(h);
    }

    public static String toHex(byte[] b) {
        return HEX.formatHex(b == null ? new byte[0] : b);
    }

    /** WWW-Authenticate Digest AKA-MD5 challenge value (without header name). */
    public static String wwwAuthenticate(String realm, byte[] rand, byte[] autn) {
        return "Digest algorithm=AKAv1-MD5, realm=\"" + realm + "\", nonce=\""
                + toHex(rand) + toHex(autn) + "\", qop=\"auth\"";
    }

    public static byte[] extractResFromAuthorization(String authorization) {
        if (authorization == null) {
            return null;
        }
        int i = authorization.toLowerCase().indexOf("response=\"");
        if (i < 0) {
            return null;
        }
        int start = i + "response=\"".length();
        int end = authorization.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return parseHex(authorization.substring(start, end));
    }

    private static byte[] sqnToBytes(long sqn) {
        byte[] out = new byte[6];
        for (int i = 5; i >= 0; i--) {
            out[i] = (byte) (sqn & 0xff);
            sqn >>= 8;
        }
        return out;
    }

    private static byte[] f1(byte[] k, byte[] opc, byte[] rand, byte[] sqn, byte[] amf) {
        byte[] in = new byte[16];
        System.arraycopy(sqn, 0, in, 0, 6);
        System.arraycopy(amf, 0, in, 6, 2);
        System.arraycopy(sqn, 0, in, 8, 6);
        System.arraycopy(amf, 0, in, 14, 2);
        byte[] temp = aes(k, xor(rand, opc));
        byte[] out = aes(k, xor(xor(in, opc), temp));
        return Arrays.copyOf(xor(out, opc), 8);
    }

    private static byte[] f2(byte[] k, byte[] opc, byte[] rand) {
        return Arrays.copyOf(f2345(k, opc, rand, 1), 8);
    }

    private static byte[] f3(byte[] k, byte[] opc, byte[] rand) {
        return f2345(k, opc, rand, 2);
    }

    private static byte[] f4(byte[] k, byte[] opc, byte[] rand) {
        return f2345(k, opc, rand, 4);
    }

    private static byte[] f2345(byte[] k, byte[] opc, byte[] rand, int r) {
        byte[] temp = aes(k, xor(rand, opc));
        byte[] rotated = rotate(temp, r);
        return xor(aes(k, xor(rotated, opc)), opc);
    }

    private static byte[] rotate(byte[] in, int rBytes) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = in[(i + rBytes) % 16];
        }
        return out;
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (a[i % a.length] ^ b[i % b.length]);
        }
        return out;
    }

    private static byte[] aes(byte[] key, byte[] data) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(padKey(key), "AES"));
            return c.doFinal(padBlock(data));
        } catch (Exception e) {
            // fallback lab hash
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                md.update(key);
                md.update(data);
                return Arrays.copyOf(md.digest(), 16);
            } catch (Exception ex) {
                return Arrays.copyOf((key.toString() + new String(data, StandardCharsets.ISO_8859_1))
                        .getBytes(StandardCharsets.ISO_8859_1), 16);
            }
        }
    }

    private static byte[] padKey(byte[] key) {
        byte[] k = new byte[16];
        System.arraycopy(key, 0, k, 0, Math.min(16, key.length));
        return k;
    }

    private static byte[] padBlock(byte[] data) {
        byte[] d = new byte[16];
        System.arraycopy(data, 0, d, 0, Math.min(16, data.length));
        return d;
    }
}
