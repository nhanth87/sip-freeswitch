package et.epc.crypto;

import java.util.HexFormat;

public final class Bytes {
    private static final HexFormat HEX = HexFormat.of();

    private Bytes() {}

    public static byte[] xor(byte[] a, byte[] b) {
        byte[] out = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = (byte) (a[i] ^ b[i % b.length]);
        }
        return out;
    }

    public static byte[] rotBytes(byte[] in, int rBytes) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[(i + rBytes) % in.length];
        }
        return out;
    }

    public static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    public static byte[] copy(byte[] in, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(in, off, out, 0, len);
        return out;
    }

    public static byte[] parseHex(String hex) {
        return HEX.parseHex(hex.trim().replace(" ", ""));
    }

    public static String toHex(byte[] b) {
        return HEX.formatHex(b);
    }

    public static byte[] sqnToBytes(long sqn) {
        byte[] out = new byte[6];
        for (int i = 5; i >= 0; i--) {
            out[i] = (byte) (sqn & 0xff);
            sqn >>= 8;
        }
        return out;
    }

    public static long bytesToSqn(byte[] b) {
        long v = 0;
        for (int i = 0; i < 6; i++) {
            v = (v << 8) | (b[i] & 0xff);
        }
        return v;
    }
}
