package et.epc.api;

import java.util.Objects;

/** AIA Authentication-Info contents (TS 29.272 / TS 33.401). */
public record EpsAuthVector(byte[] rand, byte[] xres, byte[] autn, byte[] kasme, long nextSqn) {
    public EpsAuthVector {
        Objects.requireNonNull(rand);
        Objects.requireNonNull(xres);
        Objects.requireNonNull(autn);
        Objects.requireNonNull(kasme);
        if (rand.length != 16 || autn.length != 16 || kasme.length != 32) {
            throw new IllegalArgumentException("EPS vector lengths");
        }
    }
}
