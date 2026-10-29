package et.epc.crypto;

/**
 * TS 33.102 Annex C — sequence number management in the HE/AuC (profile of C.1–C.3).
 *
 * <p>SQN (48 bits) = SEQ (43 bits) ‖ IND (5 bits). Every authentication vector gets a
 * fresh SQN: SEQ is incremented (C.3.2) and IND is allocated cyclically over the 32
 * array positions of the USIM (C.3.4), so vectors handed to different serving nodes
 * (MME, S-CSCF, VLR/SGSN) and used out of order are still accepted by the USIM, which
 * keeps one SEQ_MS per IND (C.2.2). After a synchronisation failure the HE adopts the
 * USIM's SEQ_MS recovered from AUTS (C.3.5 / §6.3.5) and continues from there.
 *
 * <p>Same parameters as Open5GS / free5gc UDM: IND length 5, increment of SEQ by 1
 * (= SQN + 32).
 */
public final class Sqn {

    /** Length of IND in bits (TS 33.102 C.1.2 recommends 5). */
    public static final int IND_BITS = 5;
    public static final long IND_MASK = (1L << IND_BITS) - 1;
    public static final int SEQ_BITS = 48 - IND_BITS;
    public static final long SEQ_MASK = (1L << SEQ_BITS) - 1;
    public static final long MAX = (1L << 48) - 1;

    private Sqn() {
    }

    public static long seq(long sqn) {
        return (sqn >>> IND_BITS) & SEQ_MASK;
    }

    public static int ind(long sqn) {
        return (int) (sqn & IND_MASK);
    }

    public static long of(long seq, int ind) {
        return ((seq & SEQ_MASK) << IND_BITS) | (ind & IND_MASK);
    }

    /**
     * The SQN for the next authentication vector after {@code current} (the last SQN the
     * HE issued, or recovered from AUTS): SEQ + 1, IND advanced cyclically.
     */
    public static long next(long current) {
        return of(seq(current) + 1, ind(current) + 1);
    }

    /**
     * Same as {@link #next(long)} as a PostgreSQL expression over a bigint column, for an
     * atomic {@code UPDATE … SET sqn = <expr> … RETURNING sqn} (two vectors never share an SQN).
     */
    public static String nextSql(String column) {
        return "((((" + column + " >> " + IND_BITS + ") + 1) & " + SEQ_MASK + ") << " + IND_BITS
                + ") | (((" + column + " & " + IND_MASK + ") + 1) & " + IND_MASK + ")";
    }
}
