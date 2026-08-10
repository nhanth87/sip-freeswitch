package et.elisa.ims.icscf;

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
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import java.util.LinkedHashMap;
import java.util.List;
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
            case SipResponseEvent e -> onResponse(e);
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
        // Co-located S-CSCF owns AKA REGISTER.
        if (roles.scscf()) {
            return;
        }
        // I-only service deploy: Mw REGISTER → home S-CSCF.
        Map<String, List<String>> ext = new LinkedHashMap<>();
        if (e.authorization() != null && !e.authorization().isBlank()) {
            ext.put("Authorization", List.of(e.authorization()));
        }
        if (e.pathHeader() != null && !e.pathHeader().isBlank()) {
            ext.put("Path", List.of(e.pathHeader()));
        }
        LOG.debug("[I-CSCF] REGISTER -> {} callId={}", scscf, e.callId());
        send(new SendRegister(e.callId(), scscf, e.fromUri(), e.toUri(), e.contactUri(),
                e.expires(), ext));
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
            Map<String, List<String>> ims = PChargingVectors.copyIms(e.imsHeaders());
            // Preserve UE/P vector; mint only if P hop skipped (direct to I).
            PChargingVectors.ensureInvite(ims, e.callId(), roles.realm());
            send(new SendInvite(e.callId(), scscf, e.fromUri(), e.sdpBody(), ims));
        }
    }

    void onResponse(SipResponseEvent e) {
        if (roles == null || !roles.icscf() || roles.scscf()) {
            return;
        }
        // Relay Mw responses (401/200 REGISTER, INVITE forwards) toward P / previous hop.
        send(SendResponse.of(e.callId(), e.statusCode(), e.reasonPhrase()));
    }

    private void send(SipOutboundCommand cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }
}
