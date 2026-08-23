package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.hss.S6aAvp;
import et.epc.hss.S6aCommand;
import et.epc.hss.S6aMessage;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S6aDiameterMapperMoreTest {

    private static DiameterRequestEvent ev(Map<Integer, String> avps) {
        return new DiameterRequestEvent(
                "sess-1", S6aDiameterMapper.S6A_APP_ID, 318, 1L, 2L,
                "mme.host", "epc.mno", "hss.epc", "epc.lab", avps);
    }

    @Test
    void isS6aRejectsOtherAppIds() {
        assertFalse(S6aDiameterMapper.isS6a(new DiameterRequestEvent(
                "s", S6aDiameterMapper.CX_APP_ID, 300, 1L, 2L,
                "a", "b", "c", "d", Map.of())));
        assertFalse(S6aDiameterMapper.isS6a(new DiameterRequestEvent(
                "s", S6aDiameterMapper.GY_APP_ID, 272, 1L, 2L,
                "a", "b", "c", "d", Map.of())));
    }

    @Test
    void toS6aMapsOriginHostFromEventWhenAvpMissing() {
        DiameterRequestEvent e = new DiameterRequestEvent(
                "sess-1", S6aDiameterMapper.S6A_APP_ID, 316, 1L, 2L,
                "mme.host", "epc.mno", "hss.epc", "epc.lab",
                Map.of(S6aAvp.USER_NAME, "001010000000001"));
        S6aMessage s6a = S6aDiameterMapper.toS6a(e);
        assertEquals(S6aCommand.ULR, s6a.command());
        assertEquals("mme.host", s6a.avpUtf8(S6aAvp.ORIGIN_HOST));
    }

    @Test
    void toS6aHexAvpsDecodeToBytes() {
        DiameterRequestEvent e = new DiameterRequestEvent(
                "sess-1", S6aDiameterMapper.S6A_APP_ID, 318, 1L, 2L,
                "mme.host", "epc.mno", "hss.epc", "epc.lab",
                Map.of(S6aAvp.USER_NAME, "001010000000001",
                        S6aAvp.RAND, "00112233445566778899aabbccddeeff"));
        S6aMessage s6a = S6aDiameterMapper.toS6a(e);
        assertEquals(16, s6a.avp(S6aAvp.RAND).length);
    }

    @Test
    void toAnswerEncodesBinaryFieldsAsHex() {
        DiameterRequestEvent e = ev(Map.of(S6aAvp.USER_NAME, "001010000000001"));
        S6aMessage aia = S6aMessage.builder(S6aCommand.AIA, false)
                .putUtf8(S6aAvp.USER_NAME, "001010000000001")
                .put(S6aAvp.RESULT_CODE, new byte[] {0, 0, 0x07, (byte) 0xd1})
                .put(S6aAvp.KASME, new byte[32])
                .build();
        SendDiameterAnswer ans = S6aDiameterMapper.toAnswer(e, aia);
        assertEquals("2001", ans.avps().get(S6aAvp.RESULT_CODE));
        assertEquals(HexFormat.of().formatHex(new byte[32]), ans.avps().get(S6aAvp.KASME));
    }

    @Test
    void commandMappingUnknownRejects() {
        DiameterRequestEvent e = new DiameterRequestEvent(
                "s", S6aDiameterMapper.S6A_APP_ID, 999, 1L, 2L,
                "a", "b", "c", "d", Map.of());
        assertThrows(IllegalArgumentException.class, () -> S6aDiameterMapper.toS6a(e));
    }

    @Test
    void commandMappingAllS6aCommands() {
        int[] codes = {318, 316, 317, 321, 319, 320, 322, 323};
        S6aCommand[] expect = {S6aCommand.AIR, S6aCommand.ULR, S6aCommand.CLR, S6aCommand.PUR,
                S6aCommand.IDR, S6aCommand.DSR, S6aCommand.RSR, S6aCommand.NOR};
        for (int i = 0; i < codes.length; i++) {
            DiameterRequestEvent e = new DiameterRequestEvent(
                    "s", S6aDiameterMapper.S6A_APP_ID, codes[i], 1L, 2L,
                    "mme", "b", "c", "d", Map.of(S6aAvp.USER_NAME, "001010000000001"));
            assertEquals(expect[i], S6aDiameterMapper.toS6a(e).command());
        }
    }
}