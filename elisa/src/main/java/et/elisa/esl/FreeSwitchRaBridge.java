package et.elisa.esl;

import et.elisa.ra.freeswitch.FreeSwitchResourceAdaptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CDI holder for the active {@link FreeSwitchResourceAdaptor} after bootstrap.
 * Admin {@link EslClient} bridges here until all call sites use {@code @InjectRa}.
 */
@ApplicationScoped
public class FreeSwitchRaBridge {

    private volatile FreeSwitchResourceAdaptor adaptor;

    public void bind(FreeSwitchResourceAdaptor adaptor) {
        this.adaptor = adaptor;
    }

    public FreeSwitchResourceAdaptor adaptor() {
        return adaptor;
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
}
