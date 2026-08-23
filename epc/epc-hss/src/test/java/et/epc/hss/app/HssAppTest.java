package et.epc.hss.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.PlmnId;
import et.epc.hss.CxService;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HssService;
import et.epc.hss.sbbs.HssSbb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HssAppTest {

    private MicroSleeContainer container;

    @AfterEach
    void tearDown() {
        if (container != null && container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    @Test
    void installRegistersSbbTypeAndMapsDiameterEvent() {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build());

        HeapHssRepository repo = new HeapHssRepository();
        HssService hss = new HssService(repo, new HeapSecretStore(), new PlmnId("001", "01"));
        CxService cx = new CxService(repo);
        HssApp app = new HssApp();

        app.install(container, hss, cx, null);

        assertTrue(container.getSbbTypeRegistry().isRegistered(HssSbb.class));
        assertEquals(HssSbb.class, container.getSbbTypeRegistry().findTypeByName("HssSbb"));
        assertTrue(app.wired().contains("SbbType:HssSbb"));
        assertTrue(app.wired().contains("EventMapping:DiameterRequestEvent->HssSbb"));
    }
}