package et.elisa.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * W2-a — Ki/OPc wrap round-trip (AES-ECB, 16-byte blocks, hex at rest).
 */
class ImsSecretCryptoTest {

    private static final byte[] KEK = ImsSecretCrypto.kekFromHex(
            "0f1e2d3c4b5a69788796a5b4c3d2e1f0");
    private static final byte[] KI = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
    private static final byte[] OPC = HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f");

    @Test
    void wrapUnwrapRoundTripPreservesKeyMaterial() {
        String kiEnc = ImsSecretCrypto.wrap(KEK, KI);
        String opcEnc = ImsSecretCrypto.wrap(KEK, OPC);

        assertNotEquals(HexFormat.of().formatHex(KI), kiEnc, "ki must not be plaintext at rest");
        assertArrayEquals(KI, ImsSecretCrypto.unwrap(KEK, kiEnc));
        assertArrayEquals(OPC, ImsSecretCrypto.unwrap(KEK, opcEnc));
    }

    @Test
    void wrongKekFailsClosed() {
        byte[] other = ImsSecretCrypto.kekFromHex("11223344556677881122334455667788");
        String enc = ImsSecretCrypto.wrap(KEK, KI);
        assertThrows(IllegalStateException.class, () -> ImsSecretCrypto.unwrap(other, enc));
    }

    @Test
    void rejectsBadLengthAndBlankKek() {
        assertThrows(IllegalArgumentException.class,
                () -> ImsSecretCrypto.wrap(KEK, new byte[15]));
        assertThrows(IllegalArgumentException.class,
                () -> ImsSecretCrypto.parseKeyHex("abcd", "ki"));
        assertThrows(IllegalArgumentException.class, () -> ImsSecretCrypto.kekFromHex(" "));
        assertNotNull(ImsSecretCrypto.parseKeyHex(
                "00112233445566778899aabbccddeeff", "ki"));
    }
}
