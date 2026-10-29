package et.epc.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** TS 33.102 Annex C: HE generation (C.3) against the USIM acceptance rule (C.2.2). */
class SqnAnnexCTest {

    /** USIM side of C.2.2 with array size a = 32 and no age limit L. */
    static final class UsimModel {
        final long[] seqMs = new long[32];

        boolean accept(long sqn) {
            int ind = Sqn.ind(sqn);
            long seq = Sqn.seq(sqn);
            if (seq <= seqMs[ind]) {
                return false; // replay / out of range → synchronisation failure
            }
            seqMs[ind] = seq;
            return true;
        }

        long sqnMsForAuts() {
            int best = 0;
            for (int i = 1; i < 32; i++) {
                if (seqMs[i] > seqMs[best]) {
                    best = i;
                }
            }
            return Sqn.of(seqMs[best], best);
        }
    }

    @Test
    void nextIncrementsSeqAndCyclesInd() {
        long s = Sqn.of(7, 31);
        long n = Sqn.next(s);
        assertEquals(8, Sqn.seq(n));
        assertEquals(0, Sqn.ind(n));
        assertEquals(Sqn.of(8, 1), Sqn.next(Sqn.of(7, 0)));
    }

    @Test
    void wrapsWithin48Bits() {
        long n = Sqn.next(Sqn.MAX);
        assertTrue(n >= 0 && n <= Sqn.MAX);
        assertEquals(0, Sqn.seq(n));
    }

    @Test
    void vectorsUsedOutOfOrderAcrossServingNodesAreAccepted() {
        UsimModel usim = new UsimModel();
        long he = 0;
        List<Long> batch = new ArrayList<>();
        for (int i = 0; i < 20; i++) { // e.g. 5 vectors each to MME, S-CSCF, VLR, SGSN
            he = Sqn.next(he);
            batch.add(he);
        }
        Collections.shuffle(batch, new Random(42));
        for (long sqn : batch) {
            assertTrue(usim.accept(sqn), "fresh vector rejected: " + Long.toHexString(sqn));
        }
    }

    @Test
    void replayIsRejectedAndResyncFromAutsRecovers() {
        UsimModel usim = new UsimModel();
        long he = 0;
        for (int i = 0; i < 40; i++) {
            he = Sqn.next(he);
            assertTrue(usim.accept(he));
        }
        assertFalse(usim.accept(he), "replay must fail");

        // HE lost state (e.g. restored DB): it issues an old SQN → USIM sends AUTS.
        long stale = Sqn.next(0);
        assertFalse(usim.accept(stale));
        long recovered = usim.sqnMsForAuts();
        assertTrue(usim.accept(Sqn.next(recovered)), "after resync the next vector is accepted");
    }
}
