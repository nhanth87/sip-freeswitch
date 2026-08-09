package et.elisa.ims.scscf;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendBye;
import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.command.SendSdpUpdate;
import com.microjainslee.ra.sipservlet.command.SipOutboundCommand;
import com.microjainslee.ra.sipservlet.events.SipByeEvent;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipResponseEvent;
import et.elisa.cluster.DialogCheckpoint;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.metrics.SipCpsMetrics;
import et.elisa.persist.ImsBinding;
import java.time.Instant;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** S-CSCF session — ISC to FreeSWITCH AS/MRF; MT via binding contact. */
public final class ScscfInviteSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(ScscfInviteSbb.class);

    private final ImsRoleFlags roles;
    private final IscAsRouter isc;
    private final BindingStore bindings;
    private final DialogCheckpointStore checkpoints;
    private final SipCpsMetrics metrics;
    private final HssLiteService hss;
    private final String nodeId;
    private final String fsNodeId;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public ScscfInviteSbb() {
        this(null, null, null, null, null, null, "elisa-1", "fallback");
    }

    public ScscfInviteSbb(ImsRoleFlags roles, IscAsRouter isc, BindingStore bindings,
                          DialogCheckpointStore checkpoints, SipCpsMetrics metrics, HssLiteService hss,
                          String nodeId, String fsNodeId) {
        this.roles = roles;
        this.isc = isc;
        this.bindings = bindings;
        this.checkpoints = checkpoints;
        this.metrics = metrics;
        this.hss = hss;
        this.nodeId = nodeId;
        this.fsNodeId = fsNodeId;
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case SipInviteEvent e -> onInvite(e);
            case SipByeEvent e -> onBye(e);
            case SipResponseEvent e -> onResponse(e);
            default -> {}
        }
    }

    void onInvite(SipInviteEvent e) {
        if (roles == null || !roles.scscf()) {
            return;
        }
        if (metrics != null) {
            metrics.onInvite();
        }
        String to = e.toUri();
        boolean terminating = bindings.findLive(to).isPresent();
        String target;
        if (terminating) {
            ImsBinding b = bindings.findLive(to).orElseThrow();
            target = b.contact;
        } else {
            String ifc = hss == null ? null : hss.findByImpu(e.fromUri()).map(s -> s.ifcJson).orElse(null);
            if (isc != null && isc.shouldInvokeAs(ifc)) {
                target = isc.asUri();
            } else {
                target = isc == null ? to : isc.asUri();
            }
        }
        if (checkpoints != null && checkpoints.enabled()) {
            checkpoints.put(new DialogCheckpoint(
                    e.callId(), null, e.fromUri(), e.toUri(), target, fsNodeId, nodeId, Instant.now()));
        }
        LOG.debug("[S-CSCF] INVITE callId={} target={} terminating={}", e.callId(), target, terminating);
        Map<String, java.util.List<String>> ims = new java.util.LinkedHashMap<>(
                e.imsHeaders() == null ? Map.of() : e.imsHeaders());
        if (!ims.containsKey("P-Asserted-Identity") && e.fromUri() != null) {
            ims.put("P-Asserted-Identity", java.util.List.of(e.fromUri()));
        }
        if (!terminating) {
            ims.put("Route", java.util.List.of("<" + target + ";lr>"));
        }
        send(new SendInvite(e.callId(), target, e.fromUri(), e.sdpBody(), ims));
    }

    void onBye(SipByeEvent e) {
        if (metrics != null) {
            metrics.onDialogEnd();
        }
        if (checkpoints != null) {
            checkpoints.remove(e.callId());
        }
        send(new SendBye(e.callId()));
        send(new SendResponse(e.callId(), 200, "OK"));
    }

    void onResponse(SipResponseEvent e) {
        if (e.isProvisional()) {
            send(new SendResponse(e.callId(), e.statusCode(), e.reasonPhrase()));
        } else if (e.isSuccess() && e.sdpBody() != null && !e.sdpBody().isEmpty()) {
            send(new SendSdpUpdate(e.callId(), e.sdpBody()));
        } else if (e.isFinal()) {
            if (e.statusCode() >= 400 && checkpoints != null) {
                checkpoints.remove(e.callId());
            }
            send(new SendResponse(e.callId(), e.statusCode(), e.reasonPhrase()));
        }
    }

    private void send(SipOutboundCommand cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }
}
