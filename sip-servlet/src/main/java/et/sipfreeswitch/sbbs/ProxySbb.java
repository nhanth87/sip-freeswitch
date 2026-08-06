package et.sipfreeswitch.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendAck;
import com.microjainslee.ra.sipservlet.command.SendBye;
import com.microjainslee.ra.sipservlet.command.SendCancel;
import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.command.SendSdpUpdate;
import com.microjainslee.ra.sipservlet.command.SipOutboundCommand;
import com.microjainslee.ra.sipservlet.events.SipAckEvent;
import com.microjainslee.ra.sipservlet.events.SipByeEvent;
import com.microjainslee.ra.sipservlet.events.SipCancelEvent;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipOptionsEvent;
import com.microjainslee.ra.sipservlet.events.SipResponseEvent;
import et.sipfreeswitch.metrics.SipCpsMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SIP proxy toward FreeSWITCH trunk. Hot-path: counters + DEBUG only (no INFO SIP dumps).
 */
public final class ProxySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(ProxySbb.class);

    private final String freeswitchTrunkUri;
    private final String trunkDomain;
    private final SipCpsMetrics metrics;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    /** Entity-pool no-arg — metrics/trunk filled by factory in bootstrap. */
    public ProxySbb() {
        this("sip:1000@127.0.0.1:5062", "freeswitch.lab", null);
    }

    public ProxySbb(String freeswitchTrunkUri, String trunkDomain, SipCpsMetrics metrics) {
        this.freeswitchTrunkUri = freeswitchTrunkUri;
        this.trunkDomain = trunkDomain;
        this.metrics = metrics;
    }

    @Override
    public void sbbCreate() {
        LOG.debug("ProxySbb created");
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
            case SipInviteEvent e -> onInvite(e);
            case SipByeEvent e -> onBye(e);
            case SipAckEvent e -> onAck(e);
            case SipCancelEvent e -> onCancel(e);
            case SipOptionsEvent e -> onOptions(e);
            case SipResponseEvent e -> onResponse(e);
            default -> LOG.trace("[ProxySbb] Unhandled: {}", event.getClass().getSimpleName());
        }
    }

    void onInvite(SipInviteEvent e) {
        if (metrics != null) {
            metrics.onInvite();
        }
        LOG.debug("[ProxySbb] INVITE callId={} domain={}", e.callId(), extractDomain(e.toUri()));
        String domain = extractDomain(e.toUri());
        String nextHop = trunkDomain.equalsIgnoreCase(domain) || domain.contains("freeswitch")
                ? freeswitchTrunkUri
                : freeswitchTrunkUri;
        send(new SendInvite(e.callId(), nextHop, e.fromUri(), e.sdpBody()));
    }

    void onBye(SipByeEvent e) {
        if (metrics != null) {
            metrics.onDialogEnd();
        }
        LOG.debug("[ProxySbb] BYE callId={}", e.callId());
        send(new SendBye(e.callId()));
    }

    void onAck(SipAckEvent e) {
        LOG.trace("[ProxySbb] ACK callId={}", e.callId());
        send(new SendAck(e.callId()));
    }

    void onCancel(SipCancelEvent e) {
        if (metrics != null) {
            metrics.onDialogEnd();
            metrics.onReject();
        }
        send(new SendCancel(e.callId()));
    }

    void onOptions(SipOptionsEvent e) {
        send(new SendResponse(e.callId(), 200, "OK"));
    }

    void onResponse(SipResponseEvent e) {
        LOG.debug("[ProxySbb] RESPONSE callId={} {}", e.callId(), e.statusCode());
        if (e.isProvisional()) {
            send(new SendResponse(e.callId(), e.statusCode(), e.reasonPhrase()));
        } else if (e.isSuccess() && e.sdpBody() != null && !e.sdpBody().isEmpty()) {
            send(new SendSdpUpdate(e.callId(), e.sdpBody()));
        } else if (e.isFinal()) {
            if (e.statusCode() >= 400 && metrics != null) {
                metrics.onReject();
                metrics.onDialogEnd();
            }
            send(new SendResponse(e.callId(), e.statusCode(), e.reasonPhrase()));
        }
    }

    private void send(SipOutboundCommand cmd) {
        RaCommandPort port = this.sipRa;
        if (port != null) {
            port.sendCommand(cmd);
        } else {
            LOG.warn("[ProxySbb] sipRa not injected — dropped {}", cmd.getClass().getSimpleName());
            if (metrics != null) {
                metrics.onReject();
            }
        }
    }

    private static String extractDomain(String uri) {
        if (uri == null) {
            return "unknown";
        }
        int atIdx = uri.indexOf('@');
        if (atIdx >= 0) {
            String afterAt = uri.substring(atIdx + 1);
            int semicolon = afterAt.indexOf(';');
            return semicolon >= 0 ? afterAt.substring(0, semicolon) : afterAt;
        }
        String stripped = uri.startsWith("sip:") ? uri.substring(4) : uri;
        int semicolon = stripped.indexOf(';');
        return semicolon >= 0 ? stripped.substring(0, semicolon) : stripped;
    }
}
