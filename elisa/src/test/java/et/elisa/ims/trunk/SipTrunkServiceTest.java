package et.elisa.ims.trunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.scscf.IscAsRouter;
import et.elisa.ims.support.ImsTestSupport;
import et.elisa.persist.SipTrunkEntity;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SipTrunkServiceTest {

    @Test
    void pickPrimary_lowestPriorityWins() {
        SipTrunkService svc = new SipTrunkService().memoryOnly("sip:fallback@lab");
        svc.upsert("high", "High", "10.0.0.2", 5060, "UDP", "sip:high@fs", true,
                null, SipTrunkEntity.KIND_ISC, 50);
        svc.upsert("low", "Low", "10.0.0.1", 5060, "UDP", "sip:low@fs", true,
                null, SipTrunkEntity.KIND_ISC, 10);
        Optional<SipTrunkEntity> pick = svc.pickPrimary(SipTrunkEntity.KIND_ISC);
        assertTrue(pick.isPresent());
        assertEquals("low", pick.get().id);
        assertEquals("sip:low@fs", svc.resolveIscOrMrfUri());
    }

    @Test
    void resolve_fallsBackToPropsWhenEmpty() {
        SipTrunkService svc = new SipTrunkService().memoryOnly("sip:1000@127.0.0.1:5062");
        assertEquals("sip:1000@127.0.0.1:5062", svc.resolveIscOrMrfUri());
    }

    @Test
    void resolve_prefersIscOverMrf() {
        SipTrunkService svc = new SipTrunkService().memoryOnly("sip:fb");
        svc.upsert("mrf", "MRF", "1.1.1.1", 5060, "UDP", "sip:mrf@fs", true,
                null, SipTrunkEntity.KIND_MRF, 1);
        svc.upsert("isc", "ISC", "2.2.2.2", 5060, "UDP", "sip:isc@fs", true,
                null, SipTrunkEntity.KIND_ISC, 99);
        assertEquals("sip:isc@fs", svc.resolveIscOrMrfUri());
    }

    @Test
    void pickPrimary_tenantBeatsShared() {
        SipTrunkService svc = new SipTrunkService().memoryOnly("sip:fb");
        svc.upsert("shared", "Shared", "1.1.1.1", 5060, "UDP", "sip:shared@fs", true,
                null, SipTrunkEntity.KIND_ISC, 1);
        svc.upsert("ten", "Tenant", "2.2.2.2", 5060, "UDP", "sip:ten@fs", true,
                "bank-a", SipTrunkEntity.KIND_ISC, 50);
        assertEquals("sip:ten@fs", svc.pickPrimary(SipTrunkEntity.KIND_ISC, "bank-a").orElseThrow().sipUri);
        assertEquals("sip:shared@fs", svc.pickPrimary(SipTrunkEntity.KIND_ISC, null).orElseThrow().sipUri);
    }

    @Test
    void iscAsRouter_prefersTrunkTableOverPropsFallback() throws Exception {
        SipTrunkService trunks = new SipTrunkService().memoryOnly("sip:props@fs");
        trunks.upsert("fs-default", "FS", "127.0.0.1", 5062, "UDP", "sip:db@fs:5062", true,
                null, SipTrunkEntity.KIND_ISC, 10);
        ImsRoleFlags roles = ImsTestSupport.roles(true, true, true, true);
        ImsTestSupport.set(roles, "iscAsUri", "");
        IscAsRouter r = new IscAsRouter();
        ImsTestSupport.set(r, "roles", roles);
        ImsTestSupport.set(r, "trunks", trunks);
        ImsTestSupport.set(r, "fallbackTrunk", "sip:props@fs");
        assertEquals("sip:db@fs:5062", r.asUri());
    }

    @Test
    void iscAsRouter_explicitIscAsUriStillWins() throws Exception {
        SipTrunkService trunks = new SipTrunkService().memoryOnly("sip:props@fs");
        trunks.upsert("fs-default", "FS", "127.0.0.1", 5062, "UDP", "sip:db@fs", true,
                null, SipTrunkEntity.KIND_ISC, 10);
        ImsRoleFlags roles = ImsTestSupport.roles(true, true, true, true);
        ImsTestSupport.set(roles, "iscAsUri", "sip:configured@10.1.1.1:5062");
        IscAsRouter r = new IscAsRouter();
        ImsTestSupport.set(r, "roles", roles);
        ImsTestSupport.set(r, "trunks", trunks);
        ImsTestSupport.set(r, "fallbackTrunk", "sip:props@fs");
        assertEquals("sip:configured@10.1.1.1:5062", r.asUri());
    }

    @Test
    void disabledTrunksSkipped() {
        SipTrunkService svc = new SipTrunkService().memoryOnly("sip:fb");
        svc.upsert("off", "Off", "1.1.1.1", 5060, "UDP", "sip:off@fs", false,
                null, SipTrunkEntity.KIND_ISC, 1);
        assertEquals("sip:fb", svc.resolveIscOrMrfUri());
    }
}
