package et.epc.pcrf.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.pcrf.HeapPccCache;
import et.epc.pcrf.PccDecision;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.PolicyRepository;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Phase C3: admin policy change → cache invalidate → RAR fan-out to every
 * active Gx session (epc_ipcan_session) over the diameter-ra command port.
 */
class PcrfPolicyChangePushTest {

    private static final String IMSI = "001010000000001";
    private static final String APN = "ims";

    private final List<OutboundCommand> sent = new ArrayList<>();
    private final RaCommandPort recordingPort = sent::add;
    private final AtomicReference<List<PccRule>> rules = new AtomicReference<>(List.of());
    private final AtomicReference<List<String>> sessions = new AtomicReference<>(List.of());
    private PcrfService pcrf;
    private PcrfSbb.$Concrete sbb;

    @BeforeEach
    void setUp() throws Exception {
        PolicyRepository repo = new PolicyRepository() {
            @Override public List<PccRule> loadRules(String imsi, String apn) {
                return rules.get();
            }
            @Override public void upsertIpCanSession(String s, String i, String a,
                                                     String u, long c, String j) {
            }
            @Override public List<String> ipCanSessionIds(String imsi, String apn) {
                return sessions.get();
            }
        };
        pcrf = new PcrfService(new HeapPccCache(repo), repo);
        sbb = new PcrfSbb.$Concrete(pcrf);
        Field f = PcrfSbb.class.getDeclaredField("diameterRa");
        f.setAccessible(true);
        f.set(sbb, recordingPort);
        sbb.setServerInitEnabled(true);
        sent.clear();
    }

    @Test
    void policyChangePushesInstallRarToEveryActiveGxSession() {
        rules.set(List.of(PccRule.imsVoiceQci1()));
        sessions.set(List.of("gx;session-1", "gx;session-2"));

        int pushed = sbb.pushRarOnPolicyChange("admin-write-1", IMSI, APN);

        assertEquals(2, pushed);
        assertEquals(2, sent.size());
        List<String> targets = new ArrayList<>();
        for (OutboundCommand cmd : sent) {
            SendDiameterRequest req = (SendDiameterRequest) cmd;
            assertEquals(PcrfSbb.GX, req.applicationId());
            assertEquals(PcrfSbb.GX_RAR_CMD, req.commandCode());
            targets.add(req.sessionId());
            DiameterAvp install = child(req, GxInstall.CHARGING_RULE_INSTALL);
            assertNotNull(install, "expected Charging-Rule-Install(1001)");
            assertEquals("ims-voice-qci1", ruleName(install));
        }
        assertEquals(List.of("gx;session-1", "gx;session-2"), targets);
    }

    private static final class GxInstall {
        static final int CHARGING_RULE_INSTALL = 1001;
        static final int CHARGING_RULE_NAME = 1005;
    }

    private static DiameterAvp child(SendDiameterRequest req, int code) {
        for (DiameterAvp a : req.avpsStructured()) {
            if (a.code() == code) {
                return a;
            }
        }
        return null;
    }

    @Test
    void policyChangedInvalidatesCacheAndReadsFreshRules() {
        rules.set(List.of(PccRule.imsSignalQci5()));
        PccDecision first = pcrf.decide(IMSI, APN);
        assertEquals("ims-signal-qci5", first.rules().get(0).name());

        // Admin rewrites the rule catalog; the cached entry must not win.
        rules.set(List.of(PccRule.imsVoiceQci1()));
        PccDecision stale = pcrf.decide(IMSI, APN);
        assertEquals("ims-signal-qci5", stale.rules().get(0).name(), "cache still hot");

        PccDecision fresh = pcrf.policyChanged(IMSI, APN);
        assertEquals("ims-voice-qci1", fresh.rules().get(0).name());
    }

    @Test
    void noActiveGxSessionsMeansNoPush() {
        rules.set(List.of(PccRule.imsVoiceQci1()));
        sessions.set(List.of());

        assertEquals(0, sbb.pushRarOnPolicyChange("admin-2", IMSI, APN));
        assertTrue(sent.isEmpty());
    }

    @Test
    void emptyFreshDecisionSkipsFanOut() {
        rules.set(List.of());
        sessions.set(List.of("gx;session-1"));

        assertEquals(0, sbb.pushRarOnPolicyChange("admin-3", IMSI, APN));
        assertTrue(sent.isEmpty());
    }

    @Test
    void disabledServerInitDropsTheFanOut() {
        rules.set(List.of(PccRule.imsVoiceQci1()));
        sessions.set(List.of("gx;session-1"));
        sbb.setServerInitEnabled(false);

        assertEquals(0, sbb.pushRarOnPolicyChange("admin-4", IMSI, APN));
        assertTrue(sent.isEmpty());
        assertFalse(sbb.serverInitEnabled());
    }

    private static String ruleName(DiameterAvp avp) {
        List<DiameterAvp> children = avp.children() == null ? List.of() : avp.children();
        for (DiameterAvp a : children) {
            if (a.code() == GxInstall.CHARGING_RULE_NAME) {
                return a.asUtf8();
            }
            String nested = ruleName(a);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}