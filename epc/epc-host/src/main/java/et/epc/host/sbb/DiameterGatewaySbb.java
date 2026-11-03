package et.epc.host.sbb;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.hss.CxAvp;
import et.epc.hss.CxService;
import et.epc.hss.CxService.CxResult;
import et.epc.hss.HssService;
import et.epc.hss.PcrfPort;
import et.epc.hss.S6aMessage;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * One Diameter application gateway SBB. {@code mapEventToSbb} keys on class, so
 * S6a and Cx share this entity; demux is {@code applicationId}.
 * Transport stays on {@code diameter-ra}.
 */
@SbbAnnotation(name = "DiameterGatewaySbb", vendor = "et.epc", version = "1.0")
public abstract class DiameterGatewaySbb extends CmpBackedSbb implements SleeEventHandler {

    public static final long S6A = 16_777_251L;
    public static final long CX = 16_777_216L;
    public static final long GX = 16_777_238L;
    public static final long RX = 16_777_236L;

    private final HssService hss;
    private final CxService cx;

    /** Gx/Rx delegation seam; null keeps the 3007 (default) answer path. */
    private volatile PcrfPort pcrf;

    /**
     * Outbound-answer completion seam (Gx CCA etc.). Answers to our own
     * {@code SendDiameterRequest} arrive as {@link DiameterAnswerEvent}; the
     * Gx wire client completes its pending CCR here. Null = drop.
     */
    private volatile Consumer<DiameterAnswerEvent> answerSink;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public DiameterGatewaySbb() {
        this(null, null);
    }

    public DiameterGatewaySbb(HssService hss, CxService cx) {
        this.hss = hss;
        this.cx = cx;
    }

    public DiameterGatewaySbb(HssService hss, CxService cx, PcrfPort pcrf) {
        this(hss, cx);
        this.pcrf = pcrf;
    }

    /** Attach (or detach, if {@code null}) the PCRF bridge without rebuilding. */
    public DiameterGatewaySbb withPcrf(PcrfPort pcrf) {
        this.pcrf = pcrf;
        return this;
    }

    /** Attach (or detach, if {@code null}) the outbound-answer router (Gx CCA). */
    public DiameterGatewaySbb withAnswerSink(Consumer<DiameterAnswerEvent> sink) {
        this.answerSink = sink;
        return this;
    }

    @CmpField("sessionId")
    public abstract String getSessionId();

    @CmpField("sessionId")
    public abstract void setSessionId(String sessionId);

    @CmpField("applicationId")
    public abstract Long getApplicationId();

    @CmpField("applicationId")
    public abstract void setApplicationId(Long applicationId);

    @Override public void sbbCreate() {}
    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof DiameterAnswerEvent ans) {
            Consumer<DiameterAnswerEvent> sink = answerSink;
            if (sink != null) {
                sink.accept(ans);
            }
            return;
        }
        if (!(event instanceof DiameterRequestEvent req)) {
            return;
        }
        setSessionId(req.sessionId());
        setApplicationId(req.applicationId());
        SendDiameterAnswer answer = switch ((int) req.applicationId()) {
            case (int) S6A -> s6a(req);
            case (int) CX -> cx(req);
            case (int) GX, (int) RX -> pcrf(req);
            default -> SendDiameterAnswer.error(
                    req.sessionId(), req.applicationId(), req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3007L);
        };
        RaCommandPort port = diameterRa;
        if (port != null && answer != null) {
            port.sendCommand(answer);
        }
    }

    /** Gx/Rx delegation. When no PCRF is bound, keep answering 3007. */
    private SendDiameterAnswer pcrf(DiameterRequestEvent req) {
        PcrfPort delegate = pcrf;
        if (delegate == null) {
            return SendDiameterAnswer.error(req.sessionId(), req.applicationId(),
                    req.commandCode(), req.hopByHopId(), req.endToEndId(), 3007L);
        }
        return delegate.handle(req);
    }

    private SendDiameterAnswer s6a(DiameterRequestEvent req) {
        if (hss == null) {
            return SendDiameterAnswer.error(req.sessionId(), S6A, req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3002L);
        }
        S6aMessage in = et.epc.host.S6aDiameterMapper.toS6a(req);
        S6aMessage out = hss.handle(in);
        return et.epc.host.S6aDiameterMapper.toAnswer(req, out);
    }

    private SendDiameterAnswer cx(DiameterRequestEvent req) {
        if (cx == null) {
            return SendDiameterAnswer.error(req.sessionId(), CX, req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3002L);
        }
        Map<Integer, String> avps = req.avps() == null ? Map.of() : req.avps();
        String impi = avps.getOrDefault(CxAvp.USER_NAME, "");
        String impu = avps.getOrDefault(CxAvp.PUBLIC_IDENTITY, "");
        CxResult result = switch (req.commandCode()) {
            case 300 -> cx.uar(impiOrBlank(impi), impuOrBlank(impu));
            case 301 -> cx.sar(impiOrBlank(impi), impuOrBlank(impu),
                    avps.get(CxAvp.SERVER_NAME),
                    assignmentType(avps.get(CxAvp.SERVER_ASSIGNMENT_TYPE)));
            case 302 -> cx.lir(impuOrBlank(impu));
            case 303 -> cx.mar(impiOrBlank(impi), impuOrBlank(impu));
            default -> CxResult.error(CxAvp.UNABLE_TO_COMPLY);
        };
        // TS 29.229 wire shape: Experimental-Result for UAA states / Cx errors, typed payload.
        return result.toAnswer(req.sessionId(), req.commandCode(), req.hopByHopId(), req.endToEndId());
    }

    private static String impiOrBlank(String impi) {
        return (impi == null || impi.isBlank()) ? null : impi;
    }

    private static String impuOrBlank(String impu) {
        return (impu == null || impu.isBlank()) ? null : impu;
    }

    private static int assignmentType(String sat) {
        if (sat == null) {
            return CxAvp.SAT_REGISTRATION;
        }
        try {
            return Integer.parseInt(sat.trim());
        } catch (NumberFormatException e) {
            return CxAvp.SAT_REGISTRATION;
        }
    }

    public static final class $Concrete extends DiameterGatewaySbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(HssService hss, CxService cx) {
            super(hss, cx);
        }

        public $Concrete(HssService hss, CxService cx, PcrfPort pcrf) {
            super(hss, cx, pcrf);
        }

        @Override public String getSessionId() { return str("sessionId"); }
        @Override public void setSessionId(String v) { put("sessionId", v); write("setSessionId", String.class, v); }
        @Override public Long getApplicationId() { return lng("applicationId"); }
        @Override public void setApplicationId(Long v) { put("applicationId", v); write("setApplicationId", Long.class, v); }

        private String str(String key) {
            Object v = local.get(key);
            return v instanceof String s ? s : null;
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
                return DiameterGatewaySbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
