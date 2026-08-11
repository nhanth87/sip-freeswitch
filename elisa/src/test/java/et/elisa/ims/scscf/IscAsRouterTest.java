package et.elisa.ims.scscf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.support.ImsTestSupport;
import java.util.List;
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
                "skip:true must not invoke AS");
    }

    @Test
    void asChain_parsesArrayAndSingle() {
        assertEquals(List.of("sip:as1@fs", "sip:as2@fs"),
                IscAsRouter.parseAsChain("{\"as\":[\"sip:as1@fs\",\"sip:as2@fs\"]}"));
        assertEquals(List.of("sip:only@fs"),
                IscAsRouter.parseAsChain("{\"as\":\"sip:only@fs\"}"));
        assertEquals(List.of("sip:c1", "sip:c2"),
                IscAsRouter.parseAsChain("{\"chain\":[{\"as\":\"sip:c1\"},{\"as\":\"sip:c2\"}]}"));
    }

    @Test
    void asChain_blankIfcUsesDefaultAs() throws Exception {
        IscAsRouter r = routerWithAs("sip:default@fs");
        assertEquals(List.of("sip:default@fs"), r.asChain(null));
        assertEquals("sip:default@fs", r.firstAsUri(""));
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
            ImsTestSupport.set(roles, "featureMmtel", true);
            IscAsRouter r = new IscAsRouter();
            ImsTestSupport.set(r, "roles", roles);
            ImsTestSupport.set(r, "trunks", null);
            ImsTestSupport.set(r, "fallbackTrunk", "sip:fb@127.0.0.1:5062");
            ImsTestSupport.set(r, "bgcfUri", "");
            return r;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
