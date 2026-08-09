package et.elisa.ims.scscf;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.ims.aka.AkaMd5;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxCodec;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** S-CSCF registrar — IMS AKA via Cx MAR + BindingStore. */
public final class ScscfRegisterSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(ScscfRegisterSbb.class);

    private final ImsRoleFlags roles;
    private final CxClient cx;
    private final HssLiteService hss;
    private final BindingStore bindings;
    private final IscThirdPartyRegister thirdParty;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public ScscfRegisterSbb() {
        this(null, null, null, null, null);
    }

    public ScscfRegisterSbb(ImsRoleFlags roles, CxClient cx, HssLiteService hss,
                            BindingStore bindings, IscThirdPartyRegister thirdParty) {
        this.roles = roles;
        this.cx = cx;
        this.hss = hss;
        this.bindings = bindings;
        this.thirdParty = thirdParty;
    }

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof SipRegisterEvent reg) {
            onRegister(reg);
        }
    }

    void onRegister(SipRegisterEvent e) {
        if (roles == null || !roles.scscf()) {
            return;
        }
        String impu = e.toUri();
        String impi = extractImpi(e.fromUri(), impu);
        String contact = e.contactUri();
        int expires = e.expires();

        if (e.authorization() == null || e.authorization().isBlank()) {
            Map<Integer, String> uaa = cx.uar(impi, impu);
            if (!"2001".equals(uaa.get(CxCodec.RESULT_CODE))) {
                send(SendResponse.of(e.callId(), 403, "Forbidden"));
                return;
            }
            Map<Integer, String> maa = cx.mar(impi, impu, e.callId());
            if (!"2001".equals(maa.get(CxCodec.RESULT_CODE))) {
                send(SendResponse.of(e.callId(), 403, "Forbidden"));
                return;
            }
            String randAutn = maa.get(CxCodec.SIP_AUTHENTICATE);
            if (randAutn == null || randAutn.length() < 32) {
                send(SendResponse.of(e.callId(), 500, "Server Internal Error"));
                return;
            }
            byte[] rand = AkaMd5.parseHex(randAutn.substring(0, 32));
            byte[] autn = AkaMd5.parseHex(randAutn.substring(32));
            String www = AkaMd5.wwwAuthenticate(roles.realm(), rand, autn);
            send(SendResponse.withHeaders(e.callId(), 401, "Unauthorized",
                    Map.of("WWW-Authenticate", www)));
            LOG.debug("[S-CSCF] AKA challenge callId={} impi={}", e.callId(), impi);
            return;
        }

        var pending = hss.peekPending(e.callId()).or(() -> hss.peekPending(impi));
        byte[] res = AkaMd5.extractResFromAuthorization(e.authorization());
        if (pending.isEmpty() || !AkaMd5.verifyRes(pending.get().xres(), res)) {
            send(SendResponse.of(e.callId(), 403, "Forbidden"));
            return;
        }
        hss.takePending(e.callId());
        hss.takePending(impi);
        hss.commitSqn(pending.get().impi(), pending.get().nextSqn());
        cx.sar(impi, impu);
        bindings.upsert(impu, contact, e.pathHeader(), e.callId(), expires);
        if (thirdParty != null) {
            thirdParty.notifyAs(impu, contact, expires);
        }
        send(SendResponse.withHeaders(e.callId(), 200, "OK", Map.of(
                "Service-Route", "<" + roles.scscfUri() + ";lr>",
                "Path", e.pathHeader() == null ? "<" + roles.pcscfUri() + ";lr>" : e.pathHeader()
        )));
        LOG.debug("[S-CSCF] REGISTER ok impu={} expires={}", impu, expires);
    }

    private static String extractImpi(String from, String impu) {
        if (from != null && from.contains("@")) {
            return from.replaceFirst("(?i)^sip:", "");
        }
        return impu == null ? "" : impu.replaceFirst("(?i)^sip:", "");
    }

    private void send(SendResponse cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }
}
