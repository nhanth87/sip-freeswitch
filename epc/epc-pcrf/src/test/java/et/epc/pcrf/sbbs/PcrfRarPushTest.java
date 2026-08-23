package et.epc.pcrf.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PcrfService;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Server-initiated Gx RAR push: PcrfSbb.fireRar emits a structured SendDiameterRequest. */
class PcrfRarPushTest {

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
    void installRarEmitsChargingRuleInstallCarryingChargingRuleDefinition() {
        sbb.fireRar("sess-rar-1", PcrfSbb.GxRarAction.INSTALL, PccRule.imsVoiceQci1());

        SendDiameterRequest req = lastRequest();
        assertEquals(PcrfSbb.GX, req.applicationId());
        assertEquals(PcrfSbb.GX_RAR_CMD, req.commandCode());
        assertEquals("sess-rar-1", req.sessionId());

        DiameterAvp install = child(req, 1001);
        assertNotNull(install, "expected Charging-Rule-Install(1001)");
        assertNotNull(child(install, 1003), "install must carry Charging-Rule-Definition(1003)");
        assertEquals("ims-voice-qci1", ruleName(install));

        DiameterAvp definition = child(install, 1003);
        DiameterAvp flowInfo = child(definition, 1058);
        assertNotNull(flowInfo, "definition must carry Flow-Information(1058)");
        DiameterAvp flowStatus = child(flowInfo, 511);
        assertNotNull(flowStatus, "Flow-Information must carry Flow-Status(511)");
        assertEquals(1L, flowStatus.asUnsigned32());
    }

    @Test
    void removeRarEmitsChargingRuleRemoveWithRuleName() {
        sbb.fireRar("sess-rar-2", PcrfSbb.GxRarAction.REMOVE, PccRule.imsVoiceQci1());

        SendDiameterRequest req = lastRequest();
        assertEquals(PcrfSbb.GX_RAR_CMD, req.commandCode());
        assertEquals("ims-voice-qci1", ruleName(child(req, 1002)));
    }

    @Test
    void fireRarOverloadUsesBoundCmpSessionId() {
        sbb.setSessionId("bound-9");
        sbb.fireRar(PcrfSbb.GxRarAction.INSTALL, PccRule.imsSignalQci5());
        assertEquals("bound-9", lastRequest().sessionId());
    }

    @Test
    void disabledServerInitDropsOutboundPush() {
        sbb.setServerInitEnabled(false);
        sbb.fireRar("sess-noop", PcrfSbb.GxRarAction.INSTALL, PccRule.imsVoiceQci1());
        assertTrue(sent.isEmpty());
    }

    private static DiameterAvp child(SendDiameterRequest req, int code) {
        for (DiameterAvp a : req.avpsStructured()) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }

    private static DiameterAvp child(DiameterAvp avp, int code) {
        for (DiameterAvp a : children(avp)) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }

    private static List<DiameterAvp> children(DiameterAvp avp) {
        return avp.children() == null ? List.of() : avp.children();
    }

    private static String ruleName(DiameterAvp avp) {
        for (DiameterAvp a : children(avp)) {
            if (a.code() == 1005) {
                return a.asUtf8();
            }
            String nested = ruleName(a);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private SendDiameterRequest lastRequest() {
        assertFalse(sent.isEmpty(), "expected an outbound SendDiameterRequest");
        return (SendDiameterRequest) sent.get(sent.size() - 1);
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        set(target, name, value);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = PcrfSbb.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}