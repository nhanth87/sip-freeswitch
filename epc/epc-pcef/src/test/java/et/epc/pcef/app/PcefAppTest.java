package et.epc.pcef.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.pcef.PcefService;
import et.epc.pcef.sbbs.PcefSbb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PcefAppTest {

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

        PcefApp app = new PcefApp();
        app.install(container, new PcefService());

        assertTrue(container.getSbbTypeRegistry().isRegistered(PcefSbb.class));
        assertEquals(PcefSbb.class, container.getSbbTypeRegistry().findTypeByName("PcefSbb"));
        assertTrue(app.wired().contains("SbbType:PcefSbb"));
        assertTrue(app.wired().contains("EventMapping:DiameterRequestEvent->PcefSbb"));
    }
}