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
import et.elisa.ims.digest.SipDigestAuth;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.reg.BindingStore;
import et.elisa.persist.ImsSubscriber;
import et.elisa.profile.ElisaProfileService;
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
    private final ElisaProfileService sleeProfiles;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public ScscfRegisterSbb() {
        this(null, null, null, null, null, null);
    }

    public ScscfRegisterSbb(ImsRoleFlags roles, CxClient cx, HssLiteService hss,
                            BindingStore bindings, IscThirdPartyRegister thirdParty) {
        this(roles, cx, hss, bindings, thirdParty, null);
    }

    public ScscfRegisterSbb(ImsRoleFlags roles, CxClient cx, HssLiteService hss,
                            BindingStore bindings, IscThirdPartyRegister thirdParty,
                            ElisaProfileService sleeProfiles) {
        this.roles = roles;
        this.cx = cx;
        this.hss = hss;
        this.bindings = bindings;
        this.thirdParty = thirdParty;
        this.sleeProfiles = sleeProfiles;
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

        if (!hasUsableAuthorization(e.authorization())) {
            Map<Integer, String> uaa = cx.uar(impi, impu);
            if (!"2001".equals(uaa.get(CxCodec.RESULT_CODE))) {
                send(SendResponse.of(e.callId(), 403, "Forbidden"));
                return;
            }
            ImsSubscriber sub = hss == null ? null
                    : hss.findByImpi(impi).or(() -> hss.findByImpu(impu)).or(() -> hss.resolveIdentity(impu))
                    .orElse(null);
            if (sub != null && sub.fsA1Hash != null && !sub.fsA1Hash.isBlank()) {
                String nonce = SipDigestAuth.newNonce();
                hss.rememberDigest(e.callId(), impi, impu, nonce, sub.fsA1Hash);
                String realm = roles.realm() != null ? roles.realm() : "ims.lab";
                send(SendResponse.withHeaders(e.callId(), 401, "Unauthorized",
                        Map.of("WWW-Authenticate", SipDigestAuth.wwwAuthenticate(realm, nonce))));
                LOG.debug("[S-CSCF] Digest challenge callId={} impi={}", e.callId(), impi);
                return;
            }
            challengeAka(e, impi, impu, cx.mar(impi, impu, e.callId()));
            return;
        }

        if (tryDigestRegister(e, impi, impu, contact, expires)) {
            return;
        }

        var pending = hss.peekPending(e.callId()).or(() -> hss.peekPending(impi));
        byte[] auts = AkaMd5.auts(e.authorization());
        if (auts != null) {
            // TS 33.203 §6.1.2 synchronisation failure: RAND of our challenge + AUTS go back
            // to the HSS (MAR, SIP-Authorization), which re-syncs and returns a fresh vector.
            byte[] rand = AkaMd5.randFromNonce(SipDigestAuth.param(e.authorization(), "nonce"));
            if (pending.isEmpty() || rand == null) {
                send(SendResponse.of(e.callId(), 403, "Forbidden"));
                return;
            }
            hss.takePending(e.callId());
            hss.takePending(impi);
            challengeAka(e, impi, impu,
                    cx.marResync(impi, impu, e.callId(), AkaMd5.toHex(rand) + AkaMd5.toHex(auts)));
            return;
        }
        if (pending.isEmpty() || !AkaMd5.verifyResponse(e.authorization(), pending.get().xres(),
                "REGISTER", pending.get().nonce())) {
            send(SendResponse.of(e.callId(), 403, "Forbidden"));
            return;
        }
        hss.takePending(e.callId());
        hss.takePending(impi);
        cx.sar(impi, impu);
        bindings.upsert(impu, contact, e.pathHeader(), e.callId(), expires,
                e.receivedHost(), e.receivedPort(), e.receivedTransport());
        mirrorSubscriberProfile(impi, impu);
        if (thirdParty != null) {
            thirdParty.notifyAs(impu, contact, expires);
        }
        send(SendResponse.withHeaders(e.callId(), 200, "OK", Map.of(
                "Service-Route", "<" + roles.scscfUri() + ";lr>",
                "Path", e.pathHeader() == null ? "<" + roles.pcscfUri() + ";lr>" : e.pathHeader()
        )));
        LOG.debug("[S-CSCF] REGISTER ok impu={} expires={}", impu, expires);
    }

    /** 401 with an RFC 3310 AKAv1-MD5 challenge built from a Cx MAA. */
    private void challengeAka(SipRegisterEvent e, String impi, String impu, Map<Integer, String> maa) {
        if (!"2001".equals(maa.get(CxCodec.RESULT_CODE))) {
            send(SendResponse.of(e.callId(), 403, "Forbidden"));
            return;
        }
        String randAutn = maa.get(CxCodec.SIP_AUTHENTICATE);
        if (randAutn == null || randAutn.length() < 64) {
            send(SendResponse.of(e.callId(), 500, "Server Internal Error"));
            return;
        }
        String xresHex = maa.get(CxCodec.SIP_AUTHORIZATION);
        if (xresHex == null || xresHex.isBlank()) {
            send(SendResponse.of(e.callId(), 500, "Server Internal Error"));
            return;
        }
        byte[] rand = AkaMd5.parseHex(randAutn.substring(0, 32));
        byte[] autn = AkaMd5.parseHex(randAutn.substring(32, 64));
        // TS 29.229: the S-CSCF keeps XRES from the MAA to verify the UE's response.
        if (hss != null) {
            hss.rememberChallenge(e.callId(), impi, impu, AkaMd5.parseHex(xresHex), AkaMd5.nonce(rand, autn));
        }
        String realm = roles.realm() != null ? roles.realm() : "ims.lab";
        send(SendResponse.withHeaders(e.callId(), 401, "Unauthorized",
                Map.of("WWW-Authenticate", AkaMd5.wwwAuthenticate(realm, rand, autn))));
        LOG.debug("[S-CSCF] AKA challenge callId={} impi={}", e.callId(), impi);
    }

    private boolean tryDigestRegister(SipRegisterEvent e, String impi, String impu,
                                      String contact, int expires) {
        if (hss == null || SipDigestAuth.isAka(e.authorization())
                || !SipDigestAuth.isMd5Digest(e.authorization())) {
            return false;
        }
        var pending = hss.peekDigest(e.callId()).or(() -> hss.peekDigest(impi));
        if (pending.isEmpty()) {
            return false;
        }
        HssLiteService.PendingDigest d = pending.get();
        if (!SipDigestAuth.verify(e.authorization(), d.ha1(), "REGISTER", d.nonce())) {
            send(SendResponse.of(e.callId(), 403, "Forbidden"));
            return true;
        }
        hss.takeDigest(e.callId());
        hss.takeDigest(impi);
        cx.sar(impi, impu);
        bindings.upsert(impu, contact, e.pathHeader(), e.callId(), expires,
                e.receivedHost(), e.receivedPort(), e.receivedTransport());
        mirrorSubscriberProfile(impi, impu);
        if (thirdParty != null) {
            thirdParty.notifyAs(impu, contact, expires);
        }
        send(SendResponse.withHeaders(e.callId(), 200, "OK", Map.of(
                "Service-Route", "<" + roles.scscfUri() + ";lr>",
                "Path", e.pathHeader() == null ? "<" + roles.pcscfUri() + ";lr>" : e.pathHeader()
        )));
        LOG.debug("[S-CSCF] Digest REGISTER ok impu={} expires={}", impu, expires);
        return true;
    }

    private void mirrorSubscriberProfile(String impi, String impu) {
        if (sleeProfiles == null || hss == null) {
            return;
        }
        ImsSubscriber row = hss.findByImpi(impi)
                .or(() -> hss.findByImpu(impu))
                .orElse(null);
        if (row != null) {
            sleeProfiles.upsertSubscriber(row);
        }
    }

    private static String extractImpi(String from, String impu) {
        if (from != null && from.contains("@")) {
            return from.replaceFirst("(?i)^sip:", "");
        }
        return impu == null ? "" : impu.replaceFirst("(?i)^sip:", "");
    }

    /**
     * True when Authorization carries a non-empty Digest response (second REGISTER).
     * Lab SIPp often sends {@code Authorization: … response=""} on the first hop —
     * treat that as unauthenticated so we issue 401, not 403.
     */
    static boolean hasUsableAuthorization(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return false;
        }
        // A resync REGISTER (RFC 3310 §3.4) carries auts, typically with an empty response.
        return AkaMd5.hasResponse(authorization) || AkaMd5.auts(authorization) != null;
    }

    private void send(SendResponse cmd) {
        RaCommandPort p = sipRa;
        if (p != null) {
            p.sendCommand(cmd);
        }
    }
}
