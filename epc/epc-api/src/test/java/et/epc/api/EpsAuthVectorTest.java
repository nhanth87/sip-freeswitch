package et.epc.api;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EpsAuthVectorTest {

    @Test
    void acceptsValidLengths() {
        new EpsAuthVector(new byte[16], new byte[8], new byte[16], new byte[32], 1);
    }

    @Test
    void rejectsShortRand() {
        assertThrows(IllegalArgumentException.class, () ->
                new EpsAuthVector(new byte[15], new byte[8], new byte[16], new byte[32], 1));
    }

    @Test
    void rejectsShortAutn() {
        assertThrows(IllegalArgumentException.class, () ->
                new EpsAuthVector(new byte[16], new byte[8], new byte[15], new byte[32], 1));
    }

    @Test
    void rejectsShortKasme() {
        assertThrows(IllegalArgumentException.class, () ->
                new EpsAuthVector(new byte[16], new byte[8], new byte[16], new byte[31], 1));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class, () ->
                new EpsAuthVector(null, new byte[8], new byte[16], new byte[32], 1));
        assertThrows(NullPointerException.class, () ->
                new EpsAuthVector(new byte[16], null, new byte[16], new byte[32], 1));
        assertThrows(NullPointerException.class, () ->
                new EpsAuthVector(new byte[16], new byte[8], null, new byte[32], 1));
        assertThrows(NullPointerException.class, () ->
                new EpsAuthVector(new byte[16], new byte[8], new byte[16], null, 1));
    }
}