package et.elisa.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class SipCpsMetricsTest {

    @Test
    void inviteAndDialogLifecycle() {
        SipCpsMetrics m = new SipCpsMetrics();
        m.onInvite();
        m.onInvite();
        assertEquals(2, m.inviteCount());
        assertEquals(2, m.activeDialogs());
        assertEquals(2, m.messageCount());
        m.onDialogEnd();
        assertEquals(1, m.activeDialogs());
        m.onReject();
        assertEquals(1, m.rejectCount());
        m.onSipMessage();
        assertEquals(3, m.messageCount());
    }

    @Test
    @Timeout(15)
    void concurrentInviteIncrements() throws Exception {
        SipCpsMetrics m = new SipCpsMetrics();
        int threads = 32;
        int each = 10_000;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> jobs = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                jobs.add(pool.submit(() -> {
                    for (int i = 0; i < each; i++) {
                        m.onInvite();
                    }
                }));
            }
            for (Future<?> job : jobs) {
                job.get();
            }
        }
        assertEquals((long) threads * each, m.inviteCount());
        assertEquals((long) threads * each, m.messageCount());
        assertEquals((long) threads * each, m.activeDialogs());
    }
}
