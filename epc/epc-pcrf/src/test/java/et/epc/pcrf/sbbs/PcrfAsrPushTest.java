package et.epc.pcrf.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.RxHandler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Server-initiated Rx push: PcrfSbb.fireAsr / fireRxRar emit SendDiameterRequest on diameter-ra. */
class PcrfAsrPushTest {

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort recordingPort = sent::add;
    private PcrfSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        sbb = new PcrfSbb.$Concrete(PcrfService.withHeapPolicy());
        inject(sbb, "diameterRa", recordingPort);
        sbb.setServerInitEnabled(true);
        sent.clear();
    }

    @Test
    void asrEmitsRxAbortSessionRequestWithAbortCause() {
        sbb.fireAsr("af-1");

        SendDiameterRequest req = lastRequest();
        assertEquals(PcrfSbb.RX, req.applicationId());
        assertEquals(PcrfSbb.ASR_CMD, req.commandCode());
        assertEquals(274, req.commandCode());
        assertEquals("af-1", req.sessionId());
        assertEquals("1", req.avps().get(RxHandler.ABORT_CAUSE));
    }

    @Test
    void rxRarEmitsReAuthRequestWithAuthorizeOnlyType() {
        sbb.fireRxRar("af-2");

        SendDiameterRequest req = lastRequest();
        assertEquals(PcrfSbb.RX, req.applicationId());
        assertEquals(PcrfSbb.RX_RAR_CMD, req.commandCode());
        assertEquals(RxHandler.RE_AUTH_REQUEST_TYPE_AUTHORIZE_ONLY,
                req.avps().get(RxHandler.RE_AUTH_REQUEST_TYPE));
    }

    @Test
    void disabledServerInitDropsAsrPush() {
        sbb.setServerInitEnabled(false);
        sbb.fireAsr("af-3");
        assertTrue(sent.isEmpty());
    }

    @Test
    void unboundDiameterRaDropsAsrPush() throws Exception {
        inject(sbb, "diameterRa", null);
        sbb.fireAsr("af-4");
        assertTrue(sent.isEmpty());
    }

    private SendDiameterRequest lastRequest() {
        assertFalse(sent.isEmpty(), "expected an outbound SendDiameterRequest");
        return (SendDiameterRequest) sent.get(sent.size() - 1);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = PcrfSbb.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}