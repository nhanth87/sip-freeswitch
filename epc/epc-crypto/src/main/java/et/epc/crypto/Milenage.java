package et.epc.crypto;

import java.util.Objects;

/**
 * 3GPP TS 35.206 Milenage (default r_i / c_i). OP_C may be supplied or derived from OP.
 */
public final class Milenage {

    private Milenage() {}

    public record Result(byte[] macA, byte[] macS, byte[] res, byte[] ck, byte[] ik,
                         byte[] ak, byte[] aks) {}

    public static byte[] opcFromOp(byte[] k, byte[] op) {
        return Bytes.xor(AesEcb.encrypt(k, op), op);
    }

    public static Result compute(byte[] k, byte[] opc, byte[] rand, byte[] sqn6, byte[] amf2) {
        Objects.requireNonNull(k);
        Objects.requireNonNull(opc);
        Objects.requireNonNull(rand);
        if (amf2 == null || amf2.length != 2) {
            // AUTN = SQN⊕AK ‖ AMF ‖ MAC-A — any other AMF length corrupts it silently.
            throw new IllegalArgumentException("AMF must be 2 octets, got "
                    + (amf2 == null ? "null" : amf2.length));
        }
        byte[] temp = AesEcb.encrypt(k, Bytes.xor(rand, opc));
        byte[] in1 = new byte[16];
        System.arraycopy(sqn6, 0, in1, 0, 6);
        System.arraycopy(amf2, 0, in1, 6, 2);
        System.arraycopy(sqn6, 0, in1, 8, 6);
        System.arraycopy(amf2, 0, in1, 14, 2);
        // r1=8 bytes, c1=0
        byte[] out1 = Bytes.xor(AesEcb.encrypt(k, Bytes.xor(Bytes.xor(temp, Bytes.rotBytes(Bytes.xor(in1, opc), 8)),
                c(0))), opc);
        byte[] out2 = out(k, opc, temp, 0, 1);   // r2=0, c2=1
        byte[] out3 = out(k, opc, temp, 4, 2);   // r3=4, c3=2
        byte[] out4 = out(k, opc, temp, 8, 4);   // r4=8, c4=4
        byte[] out5 = out(k, opc, temp, 12, 8);  // r5=12, c5=8
        return new Result(
                Bytes.copy(out1, 0, 8),
                Bytes.copy(out1, 8, 8),
                Bytes.copy(out2, 8, 8),
                out3,
                out4,
                Bytes.copy(out2, 0, 6),
                Bytes.copy(out5, 0, 6));
    }

    private static byte[] out(byte[] k, byte[] opc, byte[] temp, int rot, int cLast) {
        byte[] x = Bytes.xor(Bytes.rotBytes(Bytes.xor(temp, opc), rot), c(cLast));
        return Bytes.xor(AesEcb.encrypt(k, x), opc);
    }

    private static byte[] c(int last) {
        byte[] c = new byte[16];
        c[15] = (byte) last;
        return c;
    }
}
