package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.hss.S6aAvp;
import et.epc.hss.S6aCommand;
import et.epc.hss.S6aMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S6aDiameterMapperTest {

    @Test
    void airEventMapsToS6aAndAnswerUsesVendorCommand() {
        DiameterRequestEvent ev = new DiameterRequestEvent(
                "sess-1", S6aDiameterMapper.S6A_APP_ID, 318, 1L, 2L,
                "mme.host", "epc.mno", "hss.epc", "epc.lab",
                Map.of(S6aAvp.USER_NAME, "001010000000001"));
        assertTrue(S6aDiameterMapper.isS6a(ev));
        S6aMessage req = S6aDiameterMapper.toS6a(ev);
        assertEquals(S6aCommand.AIR, req.command());
        assertEquals("001010000000001", req.avpUtf8(S6aAvp.USER_NAME));
        S6aMessage aia = S6aMessage.builder(S6aCommand.AIA, false)
                .putUtf8(S6aAvp.USER_NAME, "001010000000001")
                .put(S6aAvp.RESULT_CODE, new byte[] {0, 0, 0x07, (byte) 0xd1})
                .build();
        SendDiameterAnswer ans = S6aDiameterMapper.toAnswer(ev, aia);
        assertEquals(S6aDiameterMapper.S6A_APP_ID, ans.applicationId());
        assertEquals(318, ans.commandCode());
        assertEquals(SendDiameterAnswer.SUCCESS, ans.resultCode());
    }
}
