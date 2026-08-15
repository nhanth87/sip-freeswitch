package et.epc.pcef;

public record ChargingId(long value) {
    public ChargingId {
        if (value <= 0) {
            throw new IllegalArgumentException("charging id must be positive");
        }
    }
}
