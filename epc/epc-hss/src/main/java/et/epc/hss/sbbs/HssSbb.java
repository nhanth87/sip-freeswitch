package et.epc.hss.sbbs;

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
import et.epc.crypto.Bytes;
import et.epc.hss.CxAvp;
import et.epc.hss.CxService;
import et.epc.hss.HssService;
import et.epc.hss.PcrfPort;
import et.epc.hss.S6aAvp;
import et.epc.hss.S6aCommand;
import et.epc.hss.S6aMessage;
import et.epc.hss.S6aSubscriptionData;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Diameter application gateway SBB owned by the HSS process — S6a / Cx / Gx / Rx.
 * Transport, CER/CEA and peer liveness stay on {@code diameter-ra}; this entity only
 * demuxes by {@code applicationId} + {@code commandCode} and returns a
 * {@code SendDiameterAnswer}. CMP holds the in-flight Session-Id / Application-Id.
 */
@SbbAnnotation(name = "HssSbb", vendor = "et.epc", version = "1.0")
public abstract class HssSbb extends CmpBackedSbb implements SleeEventHandler {

    public static final long S6A = 16_777_251L;
    public static final long CX = 16_777_216L;
    public static final long GX = 16_777_238L;
    public static final long RX = 16_777_236L;

    private static final Logger LOG = LogManager.getLogger(HssSbb.class);

    private final HssService hss;
    private final CxService cx;
    private final PcrfPort pcrf;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public HssSbb() {
        this(null, null, null);
    }

    public HssSbb(HssService hss, CxService cx, PcrfPort pcrf) {
        this.hss = hss;
        this.cx = cx;
        this.pcrf = pcrf;
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
        wireSenders();
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

    private SendDiameterAnswer s6a(DiameterRequestEvent req) {
        if (hss == null) {
            return SendDiameterAnswer.error(req.sessionId(), S6A, req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3002L);
        }
        S6aMessage in = toS6a(req);
        S6aMessage out = hss.handle(in);
        return toAnswer(req, out);
    }

    private SendDiameterAnswer cx(DiameterRequestEvent req) {
        if (cx == null) {
            return SendDiameterAnswer.error(req.sessionId(), CX, req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3002L);
        }
        Map<Integer, String> avps = req.avps() == null ? Map.of() : req.avps();
        String impi = str(avps, CxAvp.USER_NAME);
        String impu = str(avps, CxAvp.PUBLIC_IDENTITY);
        CxService.CxResult r = switch (req.commandCode()) {
            case 300 -> cx.uar(impi, impu);
            case 301 -> cx.sar(impi, impu, str(avps, CxAvp.SERVER_NAME),
                    parseInt(avps, CxAvp.SERVER_ASSIGNMENT_TYPE, CxAvp.SAT_REGISTRATION));
            case 302 -> cx.lir(impu);
            case 303 -> cx.mar(impi, impu);
            case 304 -> cx.rta();
            case 305 -> cx.ppa();
            case 306, 307 -> CxService.CxResult.error(CxAvp.UNABLE_TO_COMPLY);
            default -> CxService.CxResult.error(CxAvp.ERROR_USER_UNKNOWN);
        };
        return toCxAnswer(req, r);
    }

    private SendDiameterAnswer pcrf(DiameterRequestEvent req) {
        if (pcrf == null) {
            return SendDiameterAnswer.error(req.sessionId(), req.applicationId(), req.commandCode(),
                    req.hopByHopId(), req.endToEndId(), 3002L);
        }
        return pcrf.handle(req);
    }

    /** Point the server-initiated seams (CLR/PUR/IDR, RTR/PPR) at the RA command port. */
    private void wireSenders() {
        RaCommandPort port = diameterRa;
        if (port == null) {
            return;
        }
        if (hss != null) {
            hss.withSender(cmd -> {
                RaCommandPort p = diameterRa;
                if (p != null) {
                    p.sendCommand(cmd);
                }
            });
        }
        if (cx != null) {
            cx.withSender(cmd -> {
                RaCommandPort p = diameterRa;
                if (p != null) {
                    p.sendCommand(cmd);
                }
            });
        }
    }

    private static SendDiameterAnswer toCxAnswer(DiameterRequestEvent req, CxService.CxResult r) {
        return r.toAnswer(req.sessionId(), req.commandCode(), req.hopByHopId(), req.endToEndId());
    }

    // HELPERS

    private static String str(Map<Integer, String> avps, int code) {
        String v = avps.get(code);
        return (v == null || v.isBlank()) ? null : v;
    }

    private static int parseInt(Map<Integer, String> avps, int code, int fallback) {
        String v = str(avps, code);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    /**
     * Mirror of {@code epc-host/et/epc/host/S6aDiameterMapper} so epc-hss stays a
     * sibling module with no reverse dependency on epc-host: vendor
     * {@code ra-diameter} event &#8594; {@link S6aMessage}.
     */
    private static S6aMessage toS6a(DiameterRequestEvent ev) {
        S6aCommand cmd = command(ev.commandCode(), true);
        S6aMessage.Builder b = S6aMessage.builder(cmd, true);
        Map<Integer, String> avps = ev.avps() == null ? Map.of() : ev.avps();
        putUtf8(b, avps, S6aAvp.USER_NAME);
        putUtf8(b, avps, S6aAvp.ORIGIN_HOST);
        putUtf8(b, avps, S6aAvp.ORIGIN_REALM);
        if (avps.containsKey(S6aAvp.VISITED_PLMN_ID)) {
            b.put(S6aAvp.VISITED_PLMN_ID, hexOrRaw(avps.get(S6aAvp.VISITED_PLMN_ID)));
        }
        if (avps.containsKey(S6aAvp.RAND)) {
            b.put(S6aAvp.RAND, hexOrRaw(avps.get(S6aAvp.RAND)));
        }
        if (avps.containsKey(S6aAvp.RE_SYNCHRONIZATION_INFO)) {
            b.put(S6aAvp.RE_SYNCHRONIZATION_INFO, hexOrRaw(avps.get(S6aAvp.RE_SYNCHRONIZATION_INFO)));
        }
        if (ev.originHost() != null && b.build().avpUtf8(S6aAvp.ORIGIN_HOST) == null) {
            b.putUtf8(S6aAvp.ORIGIN_HOST, ev.originHost());
        }
        // ra-diameter decodes inbound RAND + Re-Synchronization-Info as structured octets;
        // carry them through so HssService.air() can resync even when the flat map is absent.
        b.structured(ev.avpsStructured() == null ? List.of() : ev.avpsStructured());
        return b.build();
    }

    /** Mirror of the host mapper: {@link S6aMessage} &#8594; {@code SendDiameterAnswer}. */
    private static SendDiameterAnswer toAnswer(DiameterRequestEvent req, S6aMessage rsp) {
        Map<Integer, String> avps = new LinkedHashMap<>();
        rsp.avps().forEach((code, raw) -> {
            if (isStringAvp(code)) {
                avps.put(code, new String(raw, StandardCharsets.UTF_8));
            } else if (code == S6aAvp.RESULT_CODE) {
                avps.put(code, Integer.toUnsignedString(bytesToInt(raw)));
            } else {
                avps.put(code, HexFormat.of().formatHex(raw));
            }
        });
        long result = rsp.avp(S6aAvp.RESULT_CODE) == null
                ? SendDiameterAnswer.SUCCESS
                : Integer.toUnsignedLong(bytesToInt(rsp.avp(S6aAvp.RESULT_CODE)));
        return new SendDiameterAnswer(
                req.sessionId(), S6A, req.commandCode(), result,
                req.hopByHopId(), req.endToEndId(), avps, rsp.structuredAvps());
    }

    private static boolean isStringAvp(int code) {
        return code == S6aAvp.USER_NAME
                || code == S6aAvp.SUBSCRIPTION_DATA
                || code == S6aAvp.CANCELLATION_TYPE
                || code == S6aSubscriptionData.SUBSCRIBER_STATUS
                || code == S6aSubscriptionData.NETWORK_ACCESS_MODE
                || code == S6aSubscriptionData.AMBR;
    }

    private static void putUtf8(S6aMessage.Builder b, Map<Integer, String> avps, int code) {
        String v = avps.get(code);
        if (v != null) {
            b.putUtf8(code, v);
        }
    }

    private static byte[] hexOrRaw(String s) {
        String t = s.trim().replace(" ", "");
        if (t.length() % 2 == 0 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return Bytes.parseHex(t);
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int bytesToInt(byte[] v) {
        int x = 0;
        for (byte b : v) {
            x = (x << 8) | (b & 0xff);
        }
        return x;
    }

    private static S6aCommand command(int code, boolean request) {
        return switch (code) {
            case 318 -> request ? S6aCommand.AIR : S6aCommand.AIA;
            case 316 -> request ? S6aCommand.ULR : S6aCommand.ULA;
            case 317 -> request ? S6aCommand.CLR : S6aCommand.CLA;
            case 321 -> request ? S6aCommand.PUR : S6aCommand.PUA;
            case 319 -> request ? S6aCommand.IDR : S6aCommand.IDA;
            case 320 -> request ? S6aCommand.DSR : S6aCommand.DSA;
            case 322 -> request ? S6aCommand.RSR : S6aCommand.RSA;
            case 323 -> request ? S6aCommand.NOR : S6aCommand.NOA;
            default -> throw new IllegalArgumentException("S6a command " + code);
        };
    }

    public static final class $Concrete extends HssSbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
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
                // local map when container store unbound
            }
        }

        private static Method method(String name, Class<?>... params) {
            try {
                return HssSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}