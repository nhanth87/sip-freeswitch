package et.epc.api;

import java.util.Objects;

public record Imsi(String digits) {
    public Imsi {
        Objects.requireNonNull(digits);
        String d = digits.trim();
        if (d.length() < 6 || d.length() > 15 || !d.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("IMSI must be 6–15 digits");
        }
        digits = d;
    }

    @Override
    public String toString() {
        return digits;
    }
}
