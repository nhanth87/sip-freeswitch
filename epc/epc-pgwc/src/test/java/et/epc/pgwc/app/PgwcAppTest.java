package et.epc.pgwc.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.pcef.PcefService;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import et.epc.pgwc.sbbs.PgwSbb;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PgwcAppTest {

    @Test
    void installRegistersSbbAndMapsBothEvents() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(64)
                .sbbPoolMin(1)
                .sbbPoolMax(4)
                .build());
        PgwControl pgw = pgw();
        PgwcApp app = new PgwcApp();

        app.install(container, pgw);

        assertEquals(List.of(PgwcApp.PGW_SBB), app.wired());
        assertTrue(container.getSbbTypeRegistry().isRegistered(PgwSbb.class));
        assertEquals(PgwSbb.class, container.getSbbTypeRegistry().findTypeByName(PgwcApp.PGW_SBB));
        Map<Class<? extends SleeEvent>, String> map = eventMap(container);
        assertEquals(PgwcApp.PGW_SBB, map.get(Gtpv2MessageEvent.class));
        assertEquals(PgwcApp.PGW_SBB, map.get(PfcpMessageEvent.class));
    }

    private static PgwControl pgw() throws Exception {
        return new PgwControl(EpcClusterConfig.local("epc-pgw-1"), new Ipam(), new PcefService(),
                new HeapSessionStore(), command -> {}, InetAddress.getByName("127.0.0.1"),
                new InetSocketAddress("127.0.0.1", 8805));
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<? extends SleeEvent>, String> eventMap(MicroSleeContainer container) throws Exception {
        Field f = MicroSleeContainer.class.getDeclaredField("eventToSbbMap");
        f.setAccessible(true);
        return (Map<Class<? extends SleeEvent>, String>) f.get(container);
    }
}