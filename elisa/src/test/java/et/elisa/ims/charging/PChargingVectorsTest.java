package et.elisa.ims.charging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PChargingVectorsTest {

    @Test
    void ensure_generatesStableIcidPerCallId() {
        Map<String, List<String>> a = new LinkedHashMap<>();
        Map<String, List<String>> b = new LinkedHashMap<>();
        String icid1 = PChargingVectors.ensureInvite(a, "call-xyz", "ims.lab");
        String icid2 = PChargingVectors.ensureInvite(b, "call-xyz", "ims.lab");
        assertEquals(icid1, icid2);
        assertTrue(a.get(PChargingVectors.HEADER).getFirst().contains("icid-value=" + icid1));
        assertTrue(a.get(PChargingVectors.HEADER).getFirst().contains("orig-ioi=ims.lab"));
        assertEquals(icid1, a.get(PChargingVectors.X_ELISA_ICID).getFirst());
    }

    @Test
    void ensure_preservesExistingVector() {
        Map<String, List<String>> ims = new LinkedHashMap<>();
        ims.put(PChargingVectors.HEADER, List.of("icid-value=deadbeef;orig-ioi=home.net"));
        String icid = PChargingVectors.ensureInvite(ims, "other-call", "ims.lab");
        assertEquals("deadbeef", icid);
        assertEquals("icid-value=deadbeef;orig-ioi=home.net",
                ims.get(PChargingVectors.HEADER).getFirst());
    }

    @Test
    void extract_quotedAndBare() {
        assertEquals("abc", PChargingVectors.extractIcidValue("icid-value=\"abc\";orig-ioi=x"));
        assertEquals("abc", PChargingVectors.extractIcidValue("icid-value=abc;orig-ioi=x"));
    }

    @Test
    void extract_origAndTermIoi() {
        String v = "icid-value=deadbeef;orig-ioi=home.net;term-ioi=visited.net";
        assertEquals("home.net", PChargingVectors.extractOrigIoi(v));
        assertEquals("visited.net", PChargingVectors.extractTermIoi(v));
    }
}
