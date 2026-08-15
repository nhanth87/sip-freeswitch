package et.elisa.esl;

import et.elisa.cluster.FsPairRegistry;
import com.microjainslee.ra.freeswitch.FreeSwitchRaConfig;
import com.microjainslee.ra.freeswitch.FreeSwitchResourceAdaptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI holder for the active {@link FreeSwitchResourceAdaptor} after bootstrap.
 */
@ApplicationScoped
public class FreeSwitchRaBridge {

    private volatile FreeSwitchResourceAdaptor adaptor;
    private volatile String activeFsNodeId;
    private volatile FsPairRegistry.EslTarget lastRebind;

    public void bind(FreeSwitchResourceAdaptor adaptor) {
        this.adaptor = adaptor;
    }

    public FreeSwitchResourceAdaptor adaptor() {
        return adaptor;
    }

    public String activeFsNodeId() {
        return activeFsNodeId;
    }

    public void setActiveFsNodeId(String fsNodeId) {
        this.activeFsNodeId = fsNodeId;
    }

    /** Last {@link #rebind} target (tests / R9 remap). Null until first rebind. */
    public FsPairRegistry.EslTarget lastRebind() {
        return lastRebind;
    }

    public boolean live() {
        FreeSwitchResourceAdaptor a = adaptor;
        return a != null && a.live();
    }

    /**
     * Synchronous ESL connect probe. Sets {@code eslLive} from the wire —
     * never invents REGED / live when FS is down.
     */
    public boolean probeLive() {
        FreeSwitchResourceAdaptor a = adaptor;
        if (a == null) {
            return false;
        }
        a.apiSync("status");
        return a.live();
    }

    public String api(String command) {
        FreeSwitchResourceAdaptor a = adaptor;
        if (a == null) {
            return "-ERR ESL unavailable: freeswitch-ra not bound";
        }
        return a.apiSync(command);
    }

    public void rebind(FsPairRegistry.EslTarget target) {
        if (target == null) {
            return;
        }
        lastRebind = target;
        activeFsNodeId = target.fsNodeId();
        FreeSwitchResourceAdaptor a = adaptor;
        if (a == null) {
            return;
        }
        FreeSwitchRaConfig cfg = new FreeSwitchRaConfig();
        cfg.setHost(target.host());
        cfg.setPort(target.port());
        cfg.setPassword(target.password());
        cfg.setAutoSubscribe(true);
        a.reconfigure(cfg);
        a.apiSync("status");
    }
}
