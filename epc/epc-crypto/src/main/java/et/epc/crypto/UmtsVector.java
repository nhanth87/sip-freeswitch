package et.epc.crypto;

import java.security.SecureRandom;

/**
 * TS 33.102 §6.3.2 authentication vector (quintuplet) — the AV the AuC hands to a VLR/SGSN
 * (MAP SendAuthenticationInfo, TS 29.002), and that IMS AKA uses unchanged (TS 33.203 §6.1):
 * AUTN = (SQN ⊕ AK) ‖ AMF ‖ MAC-A; XRES = f2, CK = f3, IK = f4 (MILENAGE, TS 35.206).
 *
 * <p>Also converts to a GSM triplet for 2G-only serving nodes (TS 33.102 §6.8.1.2, c2/c3).
 */
public record UmtsVector(byte[] rand, byte[] xres, byte[] ck, byte[] ik, byte[] autn, long sqn) {

    private static final SecureRandom RNG = new SecureRandom();

    public static UmtsVector generate(byte[] ki, byte[] opc, byte[] amf, long sqn) {
        byte[] rand = new byte[16];
        RNG.nextBytes(rand);
        return generate(ki, opc, amf, sqn, rand);
    }

    public static UmtsVector generate(byte[] ki, byte[] opc, byte[] amf, long sqn, byte[] rand) {
        byte[] sqn6 = Bytes.sqnToBytes(sqn);
        Milenage.Result m = Milenage.compute(ki, opc, rand, sqn6, amf);
        byte[] autn = Bytes.concat(Bytes.xor(sqn6, m.ak()), amf, m.macA());
        return new UmtsVector(rand, m.res(), m.ck(), m.ik(), autn, sqn);
    }

    /** GSM triplet (RAND, SRES, Kc) for a 2G serving node. */
    public record GsmTriplet(byte[] rand, byte[] sres, byte[] kc) {}

    public GsmTriplet toGsmTriplet() {
        return new GsmTriplet(rand, c2(xres), c3(ck, ik));
    }

    /** c2: SRES = XRES*1 ⊕ XRES*2 ⊕ XRES*3 ⊕ XRES*4 over 32-bit words (XRES zero-padded to 128 bits). */
    static byte[] c2(byte[] xres) {
        byte[] padded = new byte[16];
        System.arraycopy(xres, 0, padded, 0, Math.min(16, xres.length));
        byte[] sres = new byte[4];
        for (int i = 0; i < 16; i++) {
            sres[i % 4] ^= padded[i];
        }
        return sres;
    }

    /** c3: Kc = CK1 ⊕ CK2 ⊕ IK1 ⊕ IK2 over 64-bit halves. */
    static byte[] c3(byte[] ck, byte[] ik) {
        byte[] kc = new byte[8];
        for (int i = 0; i < 8; i++) {
            kc[i] = (byte) (ck[i] ^ ck[i + 8] ^ ik[i] ^ ik[i + 8]);
        }
        return kc;
    }
}
