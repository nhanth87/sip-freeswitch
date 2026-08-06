package et.sipfreeswitch.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * CPS / dialog counters for capacity acceptance. Hot-path increments only — no SIP body logging.
 */
@ApplicationScoped
public final class SipCpsMetrics {

    private final LongAdder invites = new LongAdder();
    private final LongAdder rejects = new LongAdder();
    private final AtomicLong dialogsActive = new AtomicLong();

    private volatile Counter inviteCounter;
    private volatile Counter rejectCounter;

    public void bind(MeterRegistry registry) {
        inviteCounter = Counter.builder("sip_invite_total")
                .description("Total INVITE received at edge")
                .register(registry);
        rejectCounter = Counter.builder("sip_invite_reject_total")
                .description("INVITEs rejected / failed at edge")
                .register(registry);
        Gauge.builder("sip_dialogs_active", dialogsActive, AtomicLong::get)
                .description("Active SIP dialogs (approx)")
                .register(registry);
    }

    public void onInvite() {
        invites.increment();
        Counter c = inviteCounter;
        if (c != null) {
            c.increment();
        }
        dialogsActive.incrementAndGet();
    }

    public void onReject() {
        rejects.increment();
        Counter c = rejectCounter;
        if (c != null) {
            c.increment();
        }
    }

    public void onDialogEnd() {
        dialogsActive.updateAndGet(v -> Math.max(0, v - 1));
    }

    public long inviteCount() {
        return invites.sum();
    }

    public long rejectCount() {
        return rejects.sum();
    }

    public long activeDialogs() {
        return dialogsActive.get();
    }
}
