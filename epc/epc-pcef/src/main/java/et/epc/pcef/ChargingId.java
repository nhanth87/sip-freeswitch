package et.epc.pcef;

/**
 * PCEF charging identifier. Zero is the {@link #NONE} sentinel used when the
 * peer process (SGW-C in split S5-C wire mode) has no charging context — the
 * charging id is owned by the PGW-C/PCEF and never crosses S5-C.
 */
public record ChargingId(long value) {
    public static final ChargingId NONE = new ChargingId(0);

    public ChargingId {
        if (value < 0) {
            throw new IllegalArgumentException("charging id must not be negative");
        }
    }
}
