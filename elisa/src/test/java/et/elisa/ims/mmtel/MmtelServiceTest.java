package et.elisa.ims.mmtel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.scscf.ScscfInviteSbb;
import et.elisa.ims.support.ImsTestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MmtelServiceTest {

    @Test
    void normalizeImpu_addsSipScheme() {
        assertEquals("sip:user@ims.lab", MmtelService.normalizeImpu("user@ims.lab"));
        assertEquals("sip:a@b", MmtelService.normalizeImpu("sip:a@b"));
        assertEquals("tel:+251911", MmtelService.normalizeImpu("tel:+251911"));
    }

    @Test
    void featureOff_skipsPrivacy() throws Exception {
        ImsRoleFlags roles = ImsTestSupport.roles(true, true, true, true);
        ImsTestSupport.set(roles, "featureMmtel", false);
        MmtelService svc = new MmtelService();
        ImsTestSupport.set(svc, "roles", roles);
        Map<String, List<String>> ims = new LinkedHashMap<>();
        ims.put("P-Asserted-Identity", List.of("sip:a@ims.lab"));
        svc.applyPrivacy(ims, "sip:a@ims.lab");
        assertTrue(ims.containsKey("P-Asserted-Identity"));
        assertFalse(ims.containsKey("Privacy"));
    }

    @Test
    void decisionFactories() {
        assertEquals(MmtelService.Action.CONTINUE, MmtelService.Decision.cont().action());
        assertEquals(603, MmtelService.Decision.barred().sipStatus());
        assertEquals("sip:fwd@ims.lab", MmtelService.Decision.divert("sip:fwd@ims.lab").divertUri());
    }

    @Test
    void looksLikeBreakout_e164AndTel() {
        assertTrue(ScscfInviteSbb.looksLikeBreakout("tel:+251911223344"));
        assertTrue(ScscfInviteSbb.looksLikeBreakout("sip:+251911223344@ims.lab"));
        assertFalse(ScscfInviteSbb.looksLikeBreakout("sip:alice@ims.lab"));
        assertFalse(ScscfInviteSbb.looksLikeBreakout("sip:1000@127.0.0.1:5062"));
    }
}
