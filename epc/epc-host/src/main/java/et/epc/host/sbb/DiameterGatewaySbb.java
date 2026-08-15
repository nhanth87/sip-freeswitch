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
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.hss.CxService;
import et.epc.hss.HssService;
import et.epc.hss.S6aMessage;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final HssService hss;
    private final CxService cx;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public DiameterGatewaySbb() {
        this(null, null);
    }

    public DiameterGatewaySbb(HssService hss, CxService cx) {
        this.hss = hss;
        this.cx = cx;
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
        if (!(event instanceof DiameterRequestEvent req)) {
            return;
        }
        setSessionId(req.sessionId());
        setApplicationId(req.applicationId());
        SendDiameterAnswer answer = switch ((int) req.applicationId()) {
            case (int) S6A -> s6a(req);
            case (int) CX -> cx(req);
            default -> SendDiameterAnswer.error(
                    req.sessionId(), req.applicationId(), req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3007L);
        };
        RaCommandPort port = diameterRa;
        if (port != null && answer != null) {
            port.sendCommand(answer);
        }
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
        String user = avps.getOrDefault(1, "");
        String serverName = avps.getOrDefault(602, "sip:scscf.ims.lab");
        String scscf = switch (req.commandCode()) {
            case 301 -> { // SAR
                try {
                    yield cx.assignScscf(new et.epc.api.Imsi(digits(user)), serverName);
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            case 300, 302 -> { // UAR / LIR
                try {
                    yield cx.locatedScscf(new et.epc.api.Imsi(digits(user)));
                } catch (RuntimeException e) {
                    yield null;
                }
            }
            default -> null;
        };
        if (scscf == null) {
            return SendDiameterAnswer.error(req.sessionId(), CX, req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 5001L);
        }
        return SendDiameterAnswer.ok(req.sessionId(), CX, req.commandCode(),
                req.hopByHopId(), req.endToEndId(), Map.of(268, "2001", 602, scscf));
    }

    private static String digits(String user) {
        if (user == null) {
            return "000000";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < user.length(); i++) {
            char c = user.charAt(i);
            if (c >= '0' && c <= '9') {
                b.append(c);
            }
        }
        return b.length() >= 6 ? b.toString() : "000000";
    }

    public static final class $Concrete extends DiameterGatewaySbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(HssService hss, CxService cx) {
            super(hss, cx);
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
