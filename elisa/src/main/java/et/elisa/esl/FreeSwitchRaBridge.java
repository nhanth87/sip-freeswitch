package et.elisa.esl;

import et.elisa.cluster.FsPairRegistry;
import et.elisa.ra.freeswitch.FreeSwitchRaConfig;
import et.elisa.ra.freeswitch.FreeSwitchResourceAdaptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI holder for the active {@link FreeSwitchResourceAdaptor} after bootstrap.
 */
@ApplicationScoped
public class FreeSwitchRaBridge {

    private volatile FreeSwitchResourceAdaptor adaptor;
    private volatile String activeFsNodeId;

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

    public boolean live() {
        FreeSwitchResourceAdaptor a = adaptor;
        return a != null && a.live();
    }

    public String api(String command) {
        FreeSwitchResourceAdaptor a = adaptor;
        if (a == null) {
            return "-ERR ESL unavailable: freeswitch-ra not bound";
        }
        return a.apiSync(command);
    }

    public void rebind(FsPairRegistry.EslTarget target) {
        FreeSwitchResourceAdaptor a = adaptor;
        if (a == null || target == null) {
            return;
        }
        FreeSwitchRaConfig cfg = new FreeSwitchRaConfig();
        cfg.setHost(target.host());
        cfg.setPort(target.port());
        cfg.setPassword(target.password());
        cfg.setAutoSubscribe(true);
        a.reconfigure(cfg);
        activeFsNodeId = target.fsNodeId();
    }
}
