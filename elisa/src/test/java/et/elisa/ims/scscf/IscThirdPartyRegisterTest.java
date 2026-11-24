package et.elisa.ims.scscf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.support.ImsTestSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The AS refresh (ESL sessions, sofia rescan) used to run on the SBB thread before the REGISTER
 * 200 OK — ~3 s on the VoLTE chain. notifyAs must return at once and coalesce a burst.
 */
class IscThirdPartyRegisterTest {

    @Test
    void notifyReturnsAtOnceAndABurstCoalesces() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicInteger refreshes = new AtomicInteger();
        IscThirdPartyRegister tp = new IscThirdPartyRegister() {
            @Override
            void refreshAs() {
                refreshes.incrementAndGet();
                firstStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        tp.roles = ImsTestSupport.roles(true, true, true, true);

        long start = System.nanoTime();
        tp.notifyAs("sip:u@ims.lab", "sip:u@10.0.0.1", 3600);
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS), "refresh runs off the caller thread");
        for (int i = 0; i < 50; i++) {
            tp.notifyAs("sip:u" + i + "@ims.lab", "sip:u@10.0.0.1", 3600);
        }
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(tookMs < 500, "51 notifies with a stuck AS took " + tookMs + " ms");

        release.countDown();
        long deadline = System.currentTimeMillis() + 2_000;
        while (refreshes.get() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Thread.sleep(100);
        assertTrue(refreshes.get() == 2, "one refresh in flight + one follow-up, got " + refreshes.get());
    }
}
