package et.epc.crypto;

import java.security.MessageDigest;

/**
 * TS 33.102 §6.3.3 / §6.3.5 — re-synchronisation: recover SQN_MS from
 * AUTS = (SQN_MS ⊕ AK*) ‖ MAC-S, with AK* = f5*(RAND) and MAC-S = f1*(SQN_MS ‖ RAND ‖ AMF*),
 * AMF* = 0x0000 (§6.3.3). Rejects an AUTS whose MAC-S does not verify.
 */
public final class Auts {

    private static final byte[] AMF_STAR = {0x00, 0x00};

    private Auts() {
    }

    /** @return SQN_MS (48-bit), or throws when AUTS is malformed or MAC-S fails */
    public static long recoverSqnMs(byte[] ki, byte[] opc, byte[] rand, byte[] auts) {
        if (auts == null || auts.length != 14) {
            throw new IllegalArgumentException("AUTS must be 14 octets");
        }
        Milenage.Result first = Milenage.compute(ki, opc, rand, new byte[6], AMF_STAR);
        byte[] sqnMs = Bytes.xor(Bytes.copy(auts, 0, 6), first.aks());
        Milenage.Result check = Milenage.compute(ki, opc, rand, sqnMs, AMF_STAR);
        if (!MessageDigest.isEqual(Bytes.copy(auts, 6, 8), check.macS())) {
            throw new IllegalArgumentException("AUTS MAC-S mismatch");
        }
        return Bytes.bytesToSqn(sqnMs);
    }
}
