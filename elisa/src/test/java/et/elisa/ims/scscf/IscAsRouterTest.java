package et.elisa.ims.scscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.support.ImsTestSupport;
import org.junit.jupiter.api.Test;

class IscAsRouterTest {

    @Test
    void blankIfc_defaultsToInvokeAs() {
        IscAsRouter r = routerWithAs("");
        assertTrue(r.shouldInvokeAs(null));
        assertTrue(r.shouldInvokeAs(""));
        assertTrue(r.shouldInvokeAs("   "));
    }

    @Test
    void ifcRequiresAsOrMrfOrMmtel() {
        IscAsRouter r = routerWithAs("sip:as@fs");
        assertTrue(r.shouldInvokeAs("{\"as\":\"sip:as@fs\"}"));
        assertTrue(r.shouldInvokeAs("MRF-conference"));
        assertTrue(r.shouldInvokeAs("mmtel"));
        assertFalse(r.shouldInvokeAs("{\"skip\":true}"),
                "BUG?: unknown ifc JSON silently skips AS — may strand MO calls");
    }

    @Test
    void asUri_prefersConfiguredOverTrunk() throws Exception {
        ImsRoleFlags roles = ImsTestSupport.roles(true, true, true, true);
        ImsTestSupport.set(roles, "iscAsUri", "sip:configured@10.1.1.1:5062");
        IscAsRouter r = new IscAsRouter();
        ImsTestSupport.set(r, "roles", roles);
        ImsTestSupport.set(r, "trunks", null);
        ImsTestSupport.set(r, "fallbackTrunk", "sip:fallback@127.0.0.1:5062");
        assertEquals("sip:configured@10.1.1.1:5062", r.asUri());
    }

    @Test
    void asUri_fallsBackToTrunk() throws Exception {
        ImsRoleFlags roles = ImsTestSupport.roles(true, true, true, true);
        ImsTestSupport.set(roles, "iscAsUri", "");
        IscAsRouter r = new IscAsRouter();
        ImsTestSupport.set(r, "roles", roles);
        ImsTestSupport.set(r, "trunks", null);
        ImsTestSupport.set(r, "fallbackTrunk", "sip:1000@127.0.0.1:5062");
        assertEquals("sip:1000@127.0.0.1:5062", r.asUri());
    }

    private static IscAsRouter routerWithAs(String as) {
        try {
            ImsRoleFlags roles = ImsTestSupport.roles(true, true, true, true);
            ImsTestSupport.set(roles, "iscAsUri", as);
            IscAsRouter r = new IscAsRouter();
            ImsTestSupport.set(r, "roles", roles);
            ImsTestSupport.set(r, "trunks", null);
            ImsTestSupport.set(r, "fallbackTrunk", "sip:fb@127.0.0.1:5062");
            return r;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
