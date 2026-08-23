package et.epc.sgwc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SgwcAppTest {

    @Test
    void installMapsBothEventTypesAndWires() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer();
        SgwcApp app = new SgwcApp();
        app.install(container, null);

        assertTrue(app.wired().contains(SgwcApp.SBB_NAME));
        assertTrue(app.wired().contains(SgwcApp.GTP_RA));
        assertTrue(app.wired().contains(SgwcApp.PFCP_RA));
        assertNotNull(container.getSbbTypeRegistry().findTypeByName(SgwcApp.SBB_NAME));

        Map<?, ?> mappings = eventMappings(container);
        assertEquals(SgwcApp.SBB_NAME, mappings.get(Gtpv2MessageEvent.class));
        assertEquals(SgwcApp.SBB_NAME, mappings.get(PfcpMessageEvent.class));
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, String> eventMappings(MicroSleeContainer container) throws Exception {
        Field f = MicroSleeContainer.class.getDeclaredField("eventToSbbMap");
        f.setAccessible(true);
        return (Map<Class<?>, String>) f.get(container);
    }
}