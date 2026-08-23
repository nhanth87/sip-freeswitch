package et.epc.pcrf.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.sbbs.PcrfSbb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PcrfAppTest {

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

        PcrfApp app = new PcrfApp();
        app.install(container, PcrfService.withHeapPolicy());

        assertTrue(container.getSbbTypeRegistry().isRegistered(PcrfSbb.class));
        assertEquals(PcrfSbb.class, container.getSbbTypeRegistry().findTypeByName("PcrfSbb"));
        assertTrue(app.wired().contains("SbbType:PcrfSbb"));
        assertTrue(app.wired().contains("EventMapping:DiameterRequestEvent->PcrfSbb"));
    }
}