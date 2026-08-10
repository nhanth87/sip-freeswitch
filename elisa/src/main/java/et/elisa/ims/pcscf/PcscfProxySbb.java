package et.elisa.ims.pcscf;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendRegister;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.command.SipOutboundCommand;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import com.microjainslee.ra.sipservlet.events.SipResponseEvent;
import et.elisa.ims.charging.PChargingVectors;
import et.elisa.ims.config.ImsRoleFlags;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * P-CSCF Gm — Path on REGISTER; P-Asserted-Identity + Route toward I/S on INVITE.
 * Lab soft-fail for sec-agree / IPsec (TS 33.203 full later).
 */
public final class PcscfProxySbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(PcscfProxySbb.class);

    private final ImsRoleFlags roles;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public PcscfProxySbb() {
        this(null);
    }

    public PcscfProxySbb(ImsRoleFlags roles) {
        this.roles = roles;
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case SipRegisterEvent e -> onRegister(e);
            case SipInviteEvent e -> onInvite(e);
            case SipResponseEvent e -> onResponse(e);
            default -> {}
        }
    }

    void onRegister(SipRegisterEvent e) {
        if (roles == null || !roles.pcscf()) {
            return;
        }
        String path = "<" + roles.pcscfUri() + ";lr>";
        LOG.debug("[P-CSCF] REGISTER Path={} callId={} (sec-agree soft-fail)", path, e.callId());
        // Co-located I/S: ScscfRegisterSbb owns AKA; Path stamped on 200 by S-CSCF.
        if (roles.scscf() || roles.icscf()) {
            return;
        }
        // P-only service deploy: Mw REGISTER → I-CSCF (same Call-ID; relay responses).
        String next = roles.icscfUri();
        Map<String, List<String>> ext = new java.util.LinkedHashMap<>();
        ext.put("Path", List.of(path));
        if (e.authorization() != null && !e.authorization().isBlank()) {
            ext.put("Authorization", List.of(e.authorization()));
        }
        if (e.pathHeader() != null && !e.pathHeader().isBlank()) {
            ext.put("Path", List.of(e.pathHeader() + "," + path));
        }
        LOG.debug("[P-CSCF] REGISTER -> {} callId={}", next, e.callId());
        send(new SendRegister(e.callId(), next, e.fromUri(), e.toUri(), e.contactUri(),
                e.expires(), ext));
    }

    void onInvite(SipInviteEvent e) {
        if (roles == null || !roles.pcscf()) {
            return;
        }
        // Co-located I or S owns session SM — avoid double SendInvite.
        if (roles.scscf() || roles.icscf()) {
            LOG.debug("[P-CSCF] INVITE observed (co-located) callId={}", e.callId());
            return;
        }
        Map<String, List<String>> ims = PChargingVectors.copyIms(e.imsHeaders());
        ims.put("P-Asserted-Identity", List.of(e.fromUri()));
        ims.put("Route", List.of("<" + roles.icscfUri() + ";lr>"));
        PChargingVectors.ensureInvite(ims, e.callId(), roles.realm());
        String next = roles.icscfUri();
        LOG.debug("[P-CSCF] INVITE -> {} callId={}", next, e.callId());
        send(new SendInvite(e.callId(), next, e.fromUri(), e.sdpBody(), ims));
    }

    void onResponse(SipResponseEvent e) {
        if (roles == null || !roles.pcscf()) {
            return;
        }
        if (roles.scscf() || roles.icscf()) {
            return;
        }
        send(SendResponse.of(e.callId(), e.statusCode(), e.reasonPhrase()));
    }

    private void send(SipOutboundCommand cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }
}
