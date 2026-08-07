package et.elisa.sbbs;

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
import et.elisa.acl.SipIngressAclService;
import et.elisa.media.MediaIceTurnPolicy;
import et.elisa.metrics.SipCpsMetrics;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SIP proxy toward FreeSWITCH trunk. Ingress ACL: unapproved peer → 403.
 * Hot-path: counters + DEBUG only (no INFO SIP dumps).
 */
public final class ProxySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(ProxySbb.class);

    private final String freeswitchTrunkUri;
    private final String trunkDomain;
    private final SipCpsMetrics metrics;
    private final SipIngressAclService acl;
    private final MediaIceTurnPolicy mediaPolicy;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public ProxySbb() {
        this("sip:1000@127.0.0.1:5062", "freeswitch.lab", null, null, null);
    }

    public ProxySbb(String freeswitchTrunkUri, String trunkDomain, SipCpsMetrics metrics,
                    SipIngressAclService acl, MediaIceTurnPolicy mediaPolicy) {
        this.freeswitchTrunkUri = freeswitchTrunkUri;
        this.trunkDomain = trunkDomain;
        this.metrics = metrics;
        this.acl = acl;
        this.mediaPolicy = mediaPolicy;
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
        String peer = SipIngressAclService.peerFromViaHeaders(e.viaHeaders());
        if (acl != null && !acl.isAllowed(peer)) {
            LOG.warn("[ProxySbb] INVITE blocked by ACL peer={} callId={}", peer, e.callId());
            if (metrics != null) {
                metrics.onReject();
            }
            send(new SendResponse(e.callId(), 403, "Forbidden"));
            return;
        }
        if (mediaPolicy != null) {
            String mediaReject = mediaPolicy.rejectReason(e.sdpBody());
            if (mediaReject != null) {
                LOG.warn("[ProxySbb] INVITE rejected media policy={} callId={}", mediaReject, e.callId());
                if (metrics != null) {
                    metrics.onReject();
                }
                send(new SendResponse(e.callId(), 488, mediaReject));
                return;
            }
        }
        if (metrics != null) {
            metrics.onInvite();
        }
        LOG.debug("[ProxySbb] INVITE callId={} peer={} domain={}", e.callId(), peer, extractDomain(e.toUri()));
        send(new SendInvite(e.callId(), freeswitchTrunkUri, e.fromUri(), e.sdpBody(), e.imsHeaders()));
    }

    void onBye(SipByeEvent e) {
        if (metrics != null) {
            metrics.onDialogEnd();
        }
        LOG.debug("[ProxySbb] BYE callId={}", e.callId());
        // Far leg first (needs dialog.remotePeer), then 200 OK to UA (RFC 3261 §15.1.2).
        send(new SendBye(e.callId()));
        send(new SendResponse(e.callId(), 200, "OK"));
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
        // OPTIONS has no Via on the typed event — allow only when ACL disabled or default allow.
        if (acl != null && acl.isEnabled() && "deny".equalsIgnoreCase(acl.defaultAction())
                && acl.ruleCount() > 0) {
            // Without peer IP we cannot approve; reject OPTIONS when allowlist is active.
            send(new SendResponse(e.callId(), 403, "Forbidden"));
            return;
        }
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
