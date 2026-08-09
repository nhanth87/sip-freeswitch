package et.elisa.ims.icscf;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendInvite;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.command.SipOutboundCommand;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** I-CSCF — Cx UAR on REGISTER, LIR on terminating INVITE. */
public final class IcscfRoutingSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(IcscfRoutingSbb.class);

    private final ImsRoleFlags roles;
    private final CxClient cx;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public IcscfRoutingSbb() {
        this(null, null);
    }

    public IcscfRoutingSbb(ImsRoleFlags roles, CxClient cx) {
        this.roles = roles;
        this.cx = cx;
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
            default -> {}
        }
    }

    void onRegister(SipRegisterEvent e) {
        if (roles == null || !roles.icscf() || cx == null) {
            return;
        }
        String impu = e.toUri();
        String impi = e.fromUri() == null ? impu : e.fromUri().replaceFirst("(?i)^sip:", "");
        Map<Integer, String> uaa = cx.uar(impi, impu);
        if (!"2001".equals(uaa.get(CxCodec.RESULT_CODE))) {
            send(new SendResponse(e.callId(), 403, "Forbidden"));
            return;
        }
        String scscf = uaa.getOrDefault(CxCodec.SERVER_NAME, roles.scscfUri());
        LOG.debug("[I-CSCF] UAR ok scscf={} callId={}", scscf, e.callId());
        // Co-located S-CSCF handles REGISTER; I-only would SendInvite-style forward (not in Phase 1).
        if (!roles.scscf()) {
            send(new SendResponse(e.callId(), 503, "S-CSCF unreachable"));
        }
    }

    void onInvite(SipInviteEvent e) {
        if (roles == null || !roles.icscf() || cx == null) {
            return;
        }
        Map<Integer, String> lia = cx.lir(e.toUri());
        if (!"2001".equals(lia.get(CxCodec.RESULT_CODE))) {
            send(new SendResponse(e.callId(), 404, "Not Found"));
            return;
        }
        String scscf = lia.getOrDefault(CxCodec.SERVER_NAME, roles.scscfUri());
        LOG.debug("[I-CSCF] LIR ok scscf={} callId={}", scscf, e.callId());
        if (!roles.scscf()) {
            send(new SendInvite(e.callId(), scscf, e.fromUri(), e.sdpBody(), e.imsHeaders()));
        }
    }

    private void send(SipOutboundCommand cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }
}
