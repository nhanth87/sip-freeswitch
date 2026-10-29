package et.epc.pcrf.sbbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.pcrf.HeapPccCache;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.PolicyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Admin PCC rule write → durable store → RAR to every live session on the APN (TS 29.212 §4.5.2). */
class PcrfRuleWriteRarTest {

    /** In-memory stand-in for epc_pcc_rule / epc_ipcan_session. */
    static final class MemRepo implements PolicyRepository {
        final Map<String, List<PccRule>> rulesByApn = new HashMap<>();
        final Map<String, List<String[]>> sessionsByApn = new HashMap<>(); // {sessionId, imsi}

        @Override public List<PccRule> loadRules(String imsi, String apn) {
            return List.copyOf(rulesByApn.getOrDefault(apn, List.of()));
        }

        @Override public void upsertIpCanSession(String sessionId, String imsi, String apn,
                                                 String ueIpv4, long chargingId, String decisionJson) {
            sessionsByApn.computeIfAbsent(apn, a -> new ArrayList<>()).add(new String[] {sessionId, imsi});
        }

        @Override public List<String> ipCanSessionIds(String imsi, String apn) {
            return sessionsByApn.getOrDefault(apn, List.of()).stream()
                    .filter(s -> s[1].equals(imsi)).map(s -> s[0]).toList();
        }

        @Override public void upsertRule(String apn, PccRule rule) {
            List<PccRule> list = rulesByApn.computeIfAbsent(apn, a -> new ArrayList<>());
            list.removeIf(r -> r.name().equals(rule.name()));
            list.add(rule);
        }

        @Override public List<String> imsisWithSessions(String apn) {
            return sessionsByApn.getOrDefault(apn, List.of()).stream().map(s -> s[1]).distinct().toList();
        }
    }

    static PccRule videoGbr() {
        return new PccRule("video-gbr", 2, 5, false, true, 2_000_000, 8_000_000, 1_000_000, 4_000_000,
                null, null, "[]", true);
    }

    @Test
    void ruleWritePushesInstallRarToEachSessionAfterCacheInvalidation() {
        MemRepo repo = new MemRepo();
        PcrfService pcrf = new PcrfService(new HeapPccCache(repo), repo);
        repo.upsertIpCanSession("gx-a", "001010000000001", "internet", "10.64.0.2", 1, "{}");
        repo.upsertIpCanSession("gx-b", "001010000000002", "internet", "10.64.0.3", 2, "{}");
        repo.upsertIpCanSession("gx-c", "001010000000003", "ims", "10.65.0.2", 3, "{}");
        pcrf.decide("001010000000001", "internet"); // warm the cache with the empty rule set

        PcrfSbb.$Concrete sbb = new PcrfSbb.$Concrete(pcrf);
        List<OutboundCommand> sent = new ArrayList<>();
        sbb.bindDiameter(sent::add);

        List<String> imsis = pcrf.updateRule("internet", videoGbr());
        int pushed = sbb.pushRarOnRuleChange("admin-1", "internet", imsis);

        assertEquals(2, pushed, "both sessions on the APN, none on ims");
        assertEquals(2, sent.size());
        assertTrue(sent.stream().allMatch(c -> c instanceof SendDiameterRequest r && r.commandCode() == 258),
                "Gx RAR (258)");
        assertEquals(List.of("video-gbr"),
                pcrf.decide("001010000000001", "internet").rules().stream().map(PccRule::name).toList(),
                "cache invalidated → fresh rule visible");
    }

    @Test
    void writeWithoutDurableRepoIsRefused() {
        PcrfService heapOnly = PcrfService.withHeapPolicy();
        assertThrows(IllegalStateException.class, () -> heapOnly.updateRule("internet", videoGbr()));
    }

    @Test
    void jdbcUpsertIsPostgresOnConflictOnApnAndName() {
        String sql = et.epc.pcrf.JdbcPolicyRepositoryAccess.upsertRuleSql();
        assertTrue(sql.contains("ON CONFLICT (apn, name) DO UPDATE"));
        assertTrue(et.epc.pcrf.JdbcPolicyRepositoryAccess.imsisOnApnSql().contains("FROM epc_ipcan_session"));
    }
}
