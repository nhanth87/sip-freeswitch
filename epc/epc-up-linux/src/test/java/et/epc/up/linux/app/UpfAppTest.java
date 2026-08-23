package et.epc.up.linux.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.sbbs.UpfSbb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UpfAppTest {

    private MicroSleeContainer container;

    @AfterEach
    void tearDown() {
        if (container != null && container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    @Test
    void installRegistersSbbTypeAndMapsProgramEvent() {
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build());

        UpfApp app = new UpfApp();
        app.install(container, new LinuxGtpAgent("gtp0"));

        assertTrue(container.getSbbTypeRegistry().isRegistered(UpfSbb.class));
        assertEquals(UpfSbb.class, container.getSbbTypeRegistry().findTypeByName("UpfSbb"));
        assertTrue(app.wired().contains("SbbType:UpfSbb"));
        assertTrue(app.wired().contains("EventMapping:UpfProgramSessionEvent->UpfSbb"));
    }
}