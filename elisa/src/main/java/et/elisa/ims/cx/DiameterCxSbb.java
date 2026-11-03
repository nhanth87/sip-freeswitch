package et.elisa.ims.cx;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.CmpField;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.api.annotations.SbbAnnotation;
import com.microjainslee.core.CmpBackedSbb;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cx application SBB. Connection/CER/CEA stays on {@code diameter-ra}.
 * CMP holds the live Session-Id so a restored entity can continue MAR/UAR.
 */
@SbbAnnotation(name = "DiameterCxSbb", vendor = "et.elisa", version = "1.0")
public abstract class DiameterCxSbb extends CmpBackedSbb implements SleeEventHandler {

    private final CxServer server;
    private final CxClient client;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public DiameterCxSbb() {
        this(null, null);
    }

    public DiameterCxSbb(CxServer server, CxClient client) {
        this.server = server;
        this.client = client;
    }

    @CmpField("sessionId")
    public abstract String getSessionId();

    @CmpField("sessionId")
    public abstract void setSessionId(String sessionId);

    @CmpField("applicationId")
    public abstract Long getApplicationId();

    @CmpField("applicationId")
    public abstract void setApplicationId(Long applicationId);

    @Override public void sbbCreate() {
        bindRa();
    }

    @Override public void sbbActivate() {
        bindRa();
    }

    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        bindRa();
        switch (event) {
            case DiameterRequestEvent e -> {
                setSessionId(e.sessionId());
                setApplicationId(e.applicationId());
                if (server != null) {
                    server.onRequest(e);
                }
            }
            case DiameterAnswerEvent e -> {
                setSessionId(e.sessionId());
                setApplicationId(e.applicationId());
                if (client != null) {
                    // Result-Code (or the 3GPP Experimental-Result-Code the RA folds into
                    // resultCode()) — answers from a remote HSS carry it outside the AVP map.
                    java.util.Map<Integer, String> avps = new java.util.LinkedHashMap<>(e.avps());
                    avps.putIfAbsent(CxCodec.RESULT_CODE, Long.toString(e.resultCode()));
                    client.onAnswer(e.sessionId(), avps);
                }
            }
            default -> {}
        }
    }

    private void bindRa() {
        if (diameterRa != null) {
            if (server != null) {
                server.bindDiameter(diameterRa);
            }
            if (client != null) {
                client.bindDiameter(diameterRa);
            }
        }
    }

    public static final class $Concrete extends DiameterCxSbb {
        private final Map<String, Object> local = new ConcurrentHashMap<>();

        public $Concrete() {
            super();
        }

        public $Concrete(CxServer server, CxClient client) {
            super(server, client);
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
                return DiameterCxSbb.class.getMethod(name, params);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
