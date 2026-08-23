package et.epc.sgwc.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.sgwc.SgwControl;
import et.epc.sgwc.SgwCreateSessionResult;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SGW-C application SBB. Owns S11 (GTPv2-C) procedures and delegates Sxa (PFCP)
 * rule programming to {@link SgwControl}. GTP Echo / Association / Heartbeat
 * stay on the vendor {@code gtpv2c-ra} / {@code pfcp-ra} transport. CMP holds
 * the in-flight S11-C TEID correlation.
 */
@SbbAnnotation(name = "SgwcSbb", vendor = "et.epc", version = "1.0")
public abstract class SgwcSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(SgwcSbb.class);

    private final SgwControl sgw;

    @InjectRa(name = "gtpv2c-ra")
    private volatile RaCommandPort gtpRa;

    @InjectRa(name = "pfcp-ra")
    private volatile RaCommandPort pfcpRa;

    public SgwcSbb() {
        this(null);
    }

    public SgwcSbb(SgwControl sgw) {
        this.sgw = sgw;
    }

    @CmpField("imsi")
    public abstract String getImsi();

    @CmpField("imsi")
    public abstract void setImsi(String imsi);

    @CmpField("apn")
    public abstract String getApn();

    @CmpField("apn")
    public abstract void setApn(String apn);

    @CmpField("s11cTeid")
    public abstract Integer getS11cTeid();

    @CmpField("s11cTeid")
    public abstract void setS11cTeid(Integer teid);

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        switch (event) {
            case Gtpv2MessageEvent gtp -> onGtp(gtp);
            case PfcpMessageEvent pfcp -> onPfcp(pfcp);
            default -> {}
        }
    }

    private void onGtp(Gtpv2MessageEvent ev) {
        Gtpv2Message msg = ev.message();
        try {
            switch (msg.type()) {
                case CREATE_SESSION_REQUEST -> createSession(ev);
                case CREATE_SESSION_RESPONSE -> s5cResponse(ev);
                case MODIFY_BEARER_REQUEST -> modifyBearer(ev);
                case DELETE_SESSION_REQUEST -> deleteSession(ev);
                case RELEASE_ACCESS_BEARERS_REQUEST -> releaseAccessBearers(ev);
                default -> LOG.debug("SgwcSbb ignore {}", msg.type());
            }
        } catch (RuntimeException e) {
            LOG.warn("SgwcSbb {} failed: {}", msg.type(), e.toString());
            send(error(msg, 73), ev.peer());
        }
    }

    /** Association / Heartbeat status only; Sxa procedures live in SgwControl. */
    private void onPfcp(PfcpMessageEvent ev) {
        PfcpMessage msg = ev.message();
        switch (msg.type()) {
            case ASSOCIATION_SETUP_REQUEST, ASSOCIATION_SETUP_RESPONSE,
                    HEARTBEAT_REQUEST, HEARTBEAT_RESPONSE ->
                    LOG.info("SgwcSbb pfcp {} seid={} seq={}", msg.type(), msg.seid(), msg.sequence());
            default -> LOG.debug("SgwcSbb ignore pfcp {}", msg.type());
        }
    }

    private void createSession(Gtpv2MessageEvent ev) {
        Imsi imsi = new Imsi(Gtpv2Ies.imsiDigits(ev.message()));
        String apnName = Gtpv2Ies.apn(ev.message());
        Fteid enbS1u = api(Gtpv2Ies.senderFteid(ev.message()));
        ApnConfig apn = apnOf(apnName);
        setImsi(imsi.digits());
        setApn(apnName);
        if (sgw == null) {
            send(error(ev.message(), 73), ev.peer());
            return;
        }
        SgwCreateSessionResult r = sgw.createSession(imsi, apn, enbS1u);
        setS11cTeid(r.sgwS11c().teid());
        send(createSessionResponse(ev.message(), r), ev.peer());
    }

    private void s5cResponse(Gtpv2MessageEvent ev) {
        if (sgw == null) {
            LOG.debug("SgwcSbb S5-C response ignored: no SgwControl");
            return;
        }
        sgw.onS5cResponse(ev.message());
    }

    private void modifyBearer(Gtpv2MessageEvent ev) {
        if (sgw == null) {
            send(error(ev.message(), 64), ev.peer());
            return;
        }
        Fteid newEnb = api(Gtpv2Ies.s1uFromBearerContext(ev.message()));
        Fteid s1u = sgw.modifyAccessByTeid(ev.message().teid(), newEnb);
        setS11cTeid(ev.message().teid());
        send(new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_RESPONSE,
                ev.message().teid(), ev.message().sequence(), ev.message().recovery(),
                List.of(Gtpv2Ies.causeAccepted(), bearerContext(gtp(s1u)))),
                ev.peer());
    }

    private void deleteSession(Gtpv2MessageEvent ev) {
        LOG.info("SgwcSbb deleteSession teid={}", ev.message().teid());
        send(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_RESPONSE,
                ev.message().teid(), ev.message().sequence(), ev.message().recovery(),
                List.of(Gtpv2Ies.causeAccepted())),
                ev.peer());
    }

    /**
     * Release Access Bearers: drop S1-U forwarding, keep the S5/Sxa checkpoint.
     * Cause 16 if the S11 TEID is known; 64 (Context not found) otherwise.
     */
    private void releaseAccessBearers(Gtpv2MessageEvent ev) {
        boolean released = sgw != null && sgw.releaseAccessByTeid(ev.message().teid());
        int cause = released ? Gtpv2Ies.CAUSE_REQUEST_ACCEPTED : Gtpv2Ies.CAUSE_CONTEXT_NOT_FOUND;
        send(new Gtpv2Message(Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE,
                ev.message().teid(), ev.message().sequence(), ev.message().recovery(),
                List.of(Gtpv2Ies.cause(cause))),
                ev.peer());
    }

    private static ApnConfig apnOf(String name) {
        return "ims".equalsIgnoreCase(name) ? ApnConfig.ims("192.0.2.10") : ApnConfig.internet();
    }

    private static Gtpv2Message createSessionResponse(Gtpv2Message req, SgwCreateSessionResult r) {
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                req.teid(), req.sequence(), req.recovery(),
                List.of(
                        Gtpv2Ies.causeAccepted(),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(gtp(r.sgwS11c()))),
                        new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(r.ue().ipv4())),
                        bearerContext(gtp(r.sgwS1u()))));
    }

    private static Gtpv2Ie bearerContext(GtpFteid s1u) {
        return new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0,
                Gtpv2Ies.encodeBearerContext(Gtpv2Ies.DEFAULT_BEARER_EBI, s1u, Gtpv2Ies.causeAccepted()));
    }

    private static GtpFteid gtp(Fteid f) {
        return new GtpFteid(f.teid(), f.address(), f.interfaceType());
    }

    private static Fteid api(GtpFteid g) {
        return new Fteid(g.teid(), g.address(), g.interfaceType());
    }

    private static Gtpv2Message error(Gtpv2Message req, int cause) {
        Gtpv2MessageType rsp = switch (req.type()) {
            case CREATE_SESSION_REQUEST -> Gtpv2MessageType.CREATE_SESSION_RESPONSE;
            case MODIFY_BEARER_REQUEST -> Gtpv2MessageType.MODIFY_BEARER_RESPONSE;
            case DELETE_SESSION_REQUEST -> Gtpv2MessageType.DELETE_SESSION_RESPONSE;
            case RELEASE_ACCESS_BEARERS_REQUEST -> Gtpv2MessageType.RELEASE_ACCESS_BEARERS_RESPONSE;
            default -> Gtpv2MessageType.CREATE_SESSION_RESPONSE;
        };
        return new Gtpv2Message(rsp, req.teid(), req.sequence(), req.recovery(),
                List.of(Gtpv2Ies.cause(cause)));
    }

    private void send(Gtpv2Message msg, InetSocketAddress peer) {
        RaCommandPort port = gtpRa;
        if (port == null) {
            LOG.warn("SgwcSbb: gtpv2c-ra not injected");
            return;
        }
        port.sendCommand(new SendGtpv2Message(msg, peer));
    }

    public static final class $Concrete extends SgwcSbb {

        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(SgwControl sgw) {
            super(sgw);
        }

        @Override public String getImsi() { return str("imsi"); }
        @Override public void setImsi(String v) { put("imsi", v); write("setImsi", String.class, v); }
        @Override public String getApn() { return str("apn"); }
        @Override public void setApn(String v) { put("apn", v); write("setApn", String.class, v); }
        @Override public Integer getS11cTeid() { return i("s11cTeid"); }
        @Override public void setS11cTeid(Integer v) { put("s11cTeid", v); write("setS11cTeid", Integer.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
        }

        private Integer i(String key) {
            Object v = local.get(key);
            return v instanceof Integer n ? n : null;
        }

        private void put(String key, Object value) {
            if (value == null) {
                local.remove(key);
            } else {
                local.put(key, value);
            }
        }

        private void write(String setter, Class<?> type, Object value) {
            try {
                cmpWrite(method(setter, type), value);
            } catch (IllegalStateException ignored) {
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return SgwcSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}