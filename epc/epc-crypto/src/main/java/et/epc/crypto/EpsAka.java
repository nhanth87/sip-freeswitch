package et.epc.crypto;

import et.epc.api.EpsAuthVector;
import et.epc.api.PlmnId;
import java.security.SecureRandom;

/** EPS-AKA vector generation (TS 33.401). SQN is the value used in AUTN (already incremented). */
public final class EpsAka {

    private static final SecureRandom RNG = new SecureRandom();

    private EpsAka() {}

    public static EpsAuthVector generate(byte[] ki, byte[] opc, byte[] amf, long sqnUsed, PlmnId sn,
                                         byte[] rand) {
        byte[] sqn = Bytes.sqnToBytes(sqnUsed);
        Milenage.Result m = Milenage.compute(ki, opc, rand, sqn, amf);
        byte[] sqnXorAk = Bytes.xor(sqn, padAk(m.ak()));
        byte[] autn = Bytes.concat(sqnXorAk, amf, m.macA());
        byte[] kasme = Kasme.derive(m.ck(), m.ik(), sn, sqnXorAk);
        return new EpsAuthVector(rand, m.res(), autn, kasme, sqnUsed + 1);
    }

    public static EpsAuthVector generate(byte[] ki, byte[] opc, byte[] amf, long sqnUsed, PlmnId sn) {
        byte[] rand = new byte[16];
        RNG.nextBytes(rand);
        return generate(ki, opc, amf, sqnUsed, sn, rand);
    }

    /** AUTS resync: recover SQN_MS from AUTS = (SQN_MS ⊕ AK_S) || MAC-S. */
    public static long sqnFromAuts(byte[] ki, byte[] opc, byte[] rand, byte[] auts) {
        return Auts.recoverSqnMs(ki, opc, rand, auts);
    }

    private static byte[] padAk(byte[] ak6) {
        return ak6; // already 6
    }
}
