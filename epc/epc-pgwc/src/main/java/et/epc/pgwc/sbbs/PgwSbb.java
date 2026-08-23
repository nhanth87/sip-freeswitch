package et.epc.pgwc.sbbs;

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
import com.microjainslee.ra.pfcp.PfcpCodec;
import com.microjainslee.ra.pfcp.PfcpIe;
import com.microjainslee.ra.pfcp.PfcpIes;
import com.microjainslee.ra.pfcp.PfcpMessage;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.SendPfcpMessage;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.pgwc.CreateSessionResult;
import et.epc.pgwc.PgwControl;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PGW-C SBB owning the S5 (GTPv2-C) and Sxb (PFCP) procedures plus PCEF
 * charging/authorization through {@link PgwControl}. Socket/codec work stays on
 * {@code gtpv2c-ra} / {@code pfcp-ra}; CMP keeps the live TEID/SEID correlate.
 */
@SbbAnnotation(name = "PgwSbb", vendor = "et.epc", version = "1.0")
public abstract class PgwSbb extends CmpBackedSbb implements SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(PgwSbb.class);

    /** TS 29.244 cause 65 — no PFCP session context for the SEID in the request. */
    private static final int CAUSE_SESSION_CONTEXT_NOT_FOUND = 65;

    private final PgwControl pgw;

    @InjectRa(name = "gtpv2c-ra")
    private volatile RaCommandPort gtpRa;

    @InjectRa(name = "pfcp-ra")
    private volatile RaCommandPort pfcpRa;

    public PgwSbb() {
        this(null);
    }

    public PgwSbb(PgwControl pgw) {
        this.pgw = pgw;
    }

    @CmpField("imsi")
    public abstract String getImsi();

    @CmpField("imsi")
    public abstract void setImsi(String imsi);

    @CmpField("apn")
    public abstract String getApn();

    @CmpField("apn")
    public abstract void setApn(String apn);

    @CmpField("s5cTeid")
    public abstract Integer getS5cTeid();

    @CmpField("s5cTeid")
    public abstract void setS5cTeid(Integer teid);

    @CmpField("pfcpSeid")
    public abstract Long getPfcpSeid();

    @CmpField("pfcpSeid")
    public abstract void setPfcpSeid(Long seid);

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
                case DELETE_SESSION_REQUEST -> deleteSession(ev);
                case MODIFY_BEARER_REQUEST -> modifyBearer(ev);
                default -> LOG.debug("PgwSbb ignore {}", msg.type());
            }
        } catch (RuntimeException e) {
            LOG.warn("PgwSbb {} failed: {}", msg.type(), e.toString());
            send(error(msg, 73), ev.peer());
        }
    }

    private void onPfcp(PfcpMessageEvent ev) {
        PfcpMessage msg = ev.message();
        switch (msg.type()) {
            case ASSOCIATION_SETUP_REQUEST, ASSOCIATION_SETUP_RESPONSE ->
                    LOG.info("PgwSbb PFCP association seq={} type={}", msg.sequence(), msg.type());
            case HEARTBEAT_REQUEST, HEARTBEAT_RESPONSE ->
                    LOG.debug("PgwSbb PFCP heartbeat seq={} type={}", msg.sequence(), msg.type());
            case SESSION_REPORT_REQUEST -> sessionReport(ev);
            default -> {
                if (msg.seid() != 0L) {
                    setPfcpSeid(msg.seid());
                }
            }
        }
    }

    /** Sxb: PGW-U usage reports -> PCEF charging; answer Session Report Response. */
    private void sessionReport(PfcpMessageEvent ev) {
        PfcpMessage msg = ev.message();
        boolean applied = false;
        try {
            if (pgw != null) {
                List<PfcpUsageReport> reports = PfcpCodec.decodeUsageReports(msg.payload());
                applied = pgw.reportUsage(msg.seid(), reports);
            }
        } catch (RuntimeException e) {
            LOG.warn("PgwSbb SESSION_REPORT_REQUEST seid={} failed: {}", msg.seid(), e.toString());
        }
        if (!applied) {
            LOG.warn("PgwSbb SESSION_REPORT_REQUEST unknown seid={} seq={}",
                    msg.seid(), msg.sequence());
        }
        int cause = applied ? PfcpIe.CAUSE_REQUEST_ACCEPTED : CAUSE_SESSION_CONTEXT_NOT_FOUND;
        sendPfcp(PfcpMessage.sessionReportResponse(msg.seid(), msg.sequence(),
                List.of(PfcpIes.cause(cause))), ev.peer());
    }

    private void sendPfcp(PfcpMessage msg, InetSocketAddress peer) {
        RaCommandPort port = pfcpRa;
        if (port == null) {
            LOG.warn("PgwSbb: pfcp-ra not injected");
            return;
        }
        port.sendCommand(new SendPfcpMessage(msg, peer));
    }

private void createSession(Gtpv2MessageEvent ev) {
        if (pgw == null) {
            send(error(ev.message(), 73), ev.peer());
            return;
        }
        Imsi imsi = new Imsi(Gtpv2Ies.imsiDigits(ev.message()));
        String apnName = Gtpv2Ies.apn(ev.message());
        GtpFteid senderG = Gtpv2Ies.senderFteid(ev.message());
        if (apnName == null || apnName.isBlank() || senderG == null) {
            send(error(ev.message(), 73), ev.peer());
            return;
        }
        Fteid sender = api(senderG);
        ApnConfig apn = apnOf(apnName);
        setImsi(imsi.digits());
        setApn(apnName);
        CreateSessionResult r = pgw.createSession(imsi, apn, sender);
        setS5cTeid(r.pgwS5c().teid());
        setPfcpSeid(r.pfcpSeid());
        send(createSessionResponse(ev.message(), r), ev.peer());
    }

    private void deleteSession(Gtpv2MessageEvent ev) {
        if (pgw != null && getImsi() != null && getApn() != null) {
            pgw.deleteSession(new Imsi(getImsi()), getApn());
        }
        Gtpv2Message msg = ev.message();
        send(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_RESPONSE,
                msg.teid(), msg.sequence(), msg.recovery(),
                List.of(Gtpv2Ies.causeAccepted())), ev.peer());
    }

    /** S5 side: PGW-C does not touch S1-U; acknowledge the bearer modify in place. */
    private void modifyBearer(Gtpv2MessageEvent ev) {
        Gtpv2Message msg = ev.message();
        send(new Gtpv2Message(Gtpv2MessageType.MODIFY_BEARER_RESPONSE,
                msg.teid(), msg.sequence(), msg.recovery(),
                List.of(Gtpv2Ies.causeAccepted())), ev.peer());
    }

    private static ApnConfig apnOf(String name) {
        return "ims".equalsIgnoreCase(name) ? ApnConfig.ims("192.0.2.10") : ApnConfig.internet();
    }

    private static Gtpv2Message error(Gtpv2Message req, int cause) {
        Gtpv2MessageType rsp = switch (req.type()) {
            case DELETE_SESSION_REQUEST -> Gtpv2MessageType.DELETE_SESSION_RESPONSE;
            case MODIFY_BEARER_REQUEST -> Gtpv2MessageType.MODIFY_BEARER_RESPONSE;
            default -> Gtpv2MessageType.CREATE_SESSION_RESPONSE;
        };
        return new Gtpv2Message(rsp, req.teid(), req.sequence(), req.recovery(),
                List.of(Gtpv2Ies.cause(cause)));
    }

    private static Gtpv2Message createSessionResponse(Gtpv2Message req, CreateSessionResult r) {
        List<Gtpv2Ie> ies = new ArrayList<>();
        ies.add(Gtpv2Ies.causeAccepted());
        ies.add(new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(gtp(r.pgwS5c()))));
        if (r.ue().ipv4() != null) {
            ies.add(new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(r.ue().ipv4())));
        }
        ies.add(bearerContext(gtp(r.pgwS5u())));
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                req.teid(), req.sequence(), req.recovery(), ies);
    }

    private static Gtpv2Ie bearerContext(GtpFteid s5u) {
        return new Gtpv2Ie(Gtpv2Ie.BEARER_CONTEXT, 0,
                Gtpv2Ies.encodeBearerContext(Gtpv2Ies.DEFAULT_BEARER_EBI, s5u, Gtpv2Ies.causeAccepted()));
    }

    private static GtpFteid gtp(Fteid f) {
        return new GtpFteid(f.teid(), f.address(), f.interfaceType());
    }

    private static Fteid api(GtpFteid g) {
        return new Fteid(g.teid(), g.address(), g.interfaceType());
    }

    private void send(Gtpv2Message msg, InetSocketAddress peer) {
        RaCommandPort port = gtpRa;
        if (port == null) {
            LOG.warn("PgwSbb: gtpv2c-ra not injected");
            return;
        }
        port.sendCommand(new SendGtpv2Message(msg, peer));
    }

    public static final class $Concrete extends PgwSbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(PgwControl pgw) {
            super(pgw);
        }

        @Override public String getImsi() { return str("imsi"); }
        @Override public void setImsi(String v) { put("imsi", v); write("setImsi", String.class, v); }
        @Override public String getApn() { return str("apn"); }
        @Override public void setApn(String v) { put("apn", v); write("setApn", String.class, v); }
        @Override public Integer getS5cTeid() { return i("s5cTeid"); }
        @Override public void setS5cTeid(Integer v) { put("s5cTeid", v); write("setS5cTeid", Integer.class, v); }
        @Override public Long getPfcpSeid() { return lng("pfcpSeid"); }
        @Override public void setPfcpSeid(Long v) { put("pfcpSeid", v); write("setPfcpSeid", Long.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
        }

        private Integer i(String key) {
            Object v = local.get(key);
            return v instanceof Integer n ? n : null;
        }

        private Long lng(String key) {
            Object v = local.get(key);
            return v instanceof Long l ? l : null;
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
                return PgwSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}