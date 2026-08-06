package et.elisa.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SelectIceCandidate;
import com.microjainslee.ra.sipservlet.events.IceCandidateEvent;
import com.microjainslee.ra.sipservlet.events.IceCompletedEvent;
import com.microjainslee.ra.sipservlet.events.IceFailedEvent;
import com.microjainslee.ra.sipservlet.stun.IceCandidateCollector.Candidate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * Prefer TURN {@code relay} candidates when present (media via ICE/TURN).
 */
public final class IceNegotiationSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(IceNegotiationSbb.class);

    private final boolean preferRelay;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public IceNegotiationSbb() {
        this(true);
    }

    public IceNegotiationSbb(boolean preferRelay) {
        this.preferRelay = preferRelay;
    }

    @Override
    public void sbbCreate() {
    }

    @Override
    public void sbbActivate() {
    }

    @Override
    public void sbbPassivate() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case IceCandidateEvent e -> onCandidates(e);
            case IceCompletedEvent e -> LOG.debug("[ICE] completed callId={} {}:{} -> {}:{}",
                    e.callId(), e.localAddress(), e.localPort(), e.remoteAddress(), e.remotePort());
            case IceFailedEvent e -> LOG.warn("[ICE] failed callId={} reason={}", e.callId(), e.reason());
            default -> {
            }
        }
    }

    void onCandidates(IceCandidateEvent event) {
        List<Candidate> candidates = event.candidates();
        if (candidates == null || candidates.isEmpty()) {
            LOG.warn("[ICE] no candidates callId={}", event.callId());
            return;
        }
        Candidate best = candidates.stream()
                .max(Comparator.comparingLong(c -> score(c)))
                .orElse(candidates.getFirst());
        LOG.debug("[ICE] select type={} {}:{} callId={}", best.type(), best.address(), best.port(), event.callId());
        RaCommandPort port = sipRa;
        if (port != null) {
            port.sendCommand(new SelectIceCandidate(event.callId(), best.address(), best.port(), best.type()));
        }
    }

    private long score(Candidate c) {
        long typeBoost = switch (c.type() == null ? "" : c.type().toLowerCase()) {
            case "relay" -> preferRelay ? 1_000_000_000L : 50_000L;
            case "srflx" -> 100_000L;
            case "host" -> 10_000L;
            default -> 1_000L;
        };
        return typeBoost + c.priority();
    }
}
