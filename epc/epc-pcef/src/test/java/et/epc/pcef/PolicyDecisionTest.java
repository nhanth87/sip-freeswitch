package et.epc.pcef;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PolicyDecisionTest {

    @Test
    void staticDefaultOpensGate() {
        PolicyDecision p = PolicyDecision.staticDefault();
        assertTrue(p.gatingOpen());
        assertEquals("static-default", p.ruleName());
        assertEquals(9, p.qos().qci());
    }

    @Test
    void imsIsQci5() {
        PolicyDecision p = PolicyDecision.ims();
        assertTrue(p.gatingOpen());
        assertEquals("ims-qci5", p.ruleName());
        assertEquals(5, p.qos().qci());
    }

    @Test
    void nullFieldsRejected() {
        assertThrows(NullPointerException.class,
                () -> new PolicyDecision(null, true, "r"));
        assertThrows(NullPointerException.class,
                () -> new PolicyDecision(et.epc.api.QosProfile.pilotDefault(), true, null));
    }
}