package et.elisa.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * CPS / dialog counters for capacity acceptance. Hot-path increments only —
 * LongAdder, no SIP body logging, no Micrometer Counter.increment per message.
 */
@ApplicationScoped
public final class SipCpsMetrics {

    private final LongAdder invites = new LongAdder();
    private final LongAdder rejects = new LongAdder();
    private final LongAdder messages = new LongAdder();
    private final AtomicLong dialogsActive = new AtomicLong();

    public void bind(MeterRegistry registry) {
        Gauge.builder("sip_invite_total", invites, LongAdder::sum)
                .description("Total INVITE received at edge")
                .register(registry);
        Gauge.builder("sip_invite_reject_total", rejects, LongAdder::sum)
                .description("INVITEs rejected / failed at edge")
                .register(registry);
        Gauge.builder("sip_message_total", messages, LongAdder::sum)
                .description("SIP requests+responses counted at this process")
                .register(registry);
        Gauge.builder("sip_dialogs_active", dialogsActive, AtomicLong::get)
                .description("Active SIP dialogs (approx)")
                .register(registry);
    }

    public void onSipMessage() {
        messages.increment();
    }

    public void onInvite() {
        onSipMessage();
        invites.increment();
        dialogsActive.incrementAndGet();
    }

    public void onReject() {
        rejects.increment();
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

    public long messageCount() {
        return messages.sum();
    }

    public long activeDialogs() {
        return dialogsActive.get();
    }
}
