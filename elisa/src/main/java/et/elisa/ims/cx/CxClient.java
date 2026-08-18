package et.elisa.ims.cx;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.hss.HssLiteService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Cx client: when this JVM is HSS ({@code sf.ims.role.hss=true}), short-circuit
 * to HSS-lite. Otherwise send Diameter Cx only when {@link DiameterRaBridge#peerReady()}
 * (CER/CEA) — never LISTEN-only fiction. If the peer is down, fall back to PG
 * HSS-lite so lab REGISTER is not 5012.
 */
@ApplicationScoped
public class CxClient {

    private static final Logger LOG = Logger.getLogger(CxClient.class);

    @Inject ImsRoleFlags roles;
    @Inject HssLiteService hss;
    @Inject DiameterRaBridge diameter;

    @ConfigProperty(name = "sf.ims.cx.destination-host", defaultValue = "hss.ims.lab")
    String destHost;

    @ConfigProperty(name = "sf.ims.cx.destination-realm", defaultValue = "ims.lab")
    String destRealm;

    private volatile RaCommandPort diameterRa;
    private final ConcurrentHashMap<String, Consumer<Map<Integer, String>>> waiters = new ConcurrentHashMap<>();

    public void bindDiameter(RaCommandPort port) {
        this.diameterRa = port;
    }

    public Map<Integer, String> mar(String impi, String impu, String callId) {
        return dispatch(CxCodec.MAR, CxCodec.marAvps(impi, impu, roles.scscfUri()),
                () -> hss.handleMar(impi, impu, callId));
    }

    public Map<Integer, String> uar(String impi, String impu) {
        return dispatch(CxCodec.UAR, CxCodec.uarAvps(impi, impu),
                () -> hss.handleUar(impi, impu));
    }

    public Map<Integer, String> lir(String impu) {
        return dispatch(CxCodec.LIR, CxCodec.lirAvps(impu),
                () -> hss.handleLir(impu));
    }

    public Map<Integer, String> sar(String impi, String impu) {
        return dispatch(CxCodec.SAR, CxCodec.sarAvps(impi, impu, roles.scscfUri()),
                () -> hss.handleSar(impi, impu));
    }

    /**
     * Local HSS-lite when this JVM is the HSS. Otherwise Diameter Cx only when
     * CER/CEA {@code peerReady}; if the peer is down, lab falls back to PG
     * HSS-lite so REGISTER is not 5012 — never treat LISTEN as ready.
     */
    private Map<Integer, String> dispatch(int cmd, Map<Integer, String> avps,
                                          java.util.function.Supplier<Map<Integer, String>> local) {
        if (roles != null && roles.hss()) {
            return local.get();
        }
        if (diameter != null && diameter.peerReady()) {
            return sendSync(cmd, avps);
        }
        if (hss != null) {
            LOG.warnf("Cx remote cmd=%d peerReady=false — HSS-lite fallback (LISTEN≠peer)", cmd);
            return local.get();
        }
        return sendSync(cmd, avps);
    }

    public void onAnswer(String sessionId, Map<Integer, String> avps) {
        Consumer<Map<Integer, String>> c = waiters.remove(sessionId);
        if (c != null) {
            c.accept(avps);
        }
    }

    private Map<Integer, String> sendSync(int cmd, Map<Integer, String> avps) {
        // Remote Cx: never short-circuit to HSS-lite when role.hss=false.
        // Peer truth = CER/CEA ready — not RA bound / LISTEN alone.
        if (diameter != null && !diameter.peerReady()) {
            boolean bound = diameter.bound();
            LOG.warnf("Cx remote refused cmd=%d: peerReady=false bound=%s (LISTEN≠ready)",
                    cmd, bound);
            return Map.of(CxCodec.RESULT_CODE, "5012");
        }
        RaCommandPort port = diameterRa;
        if (port == null) {
            LOG.warn("diameter-ra not bound — Cx remote unavailable");
            return Map.of(CxCodec.RESULT_CODE, "5012");
        }
        String sid = "cx-" + UUID.randomUUID();
        Object lock = new Object();
        Map<Integer, String>[] box = new Map[1];
        waiters.put(sid, m -> {
            synchronized (lock) {
                box[0] = m;
                lock.notifyAll();
            }
        });
        port.sendCommand(new SendDiameterRequest(sid, CxCodec.CX_APP_ID, cmd, destHost, destRealm, avps));
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 3000;
            while (box[0] == null && System.currentTimeMillis() < deadline) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        waiters.remove(sid);
        return box[0] != null ? box[0] : Map.of(CxCodec.RESULT_CODE, "5004");
    }
}
