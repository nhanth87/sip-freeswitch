package et.epc.api;

public record BearerId(int ebi) {
    public BearerId {
        if (ebi < 5 || ebi > 15) {
            throw new IllegalArgumentException("EBI must be 5–15");
        }
    }
}
