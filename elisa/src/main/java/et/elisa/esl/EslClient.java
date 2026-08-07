package et.elisa.esl;

import et.elisa.ra.freeswitch.FreeSwitchResourceAdaptor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Admin ESL facade — delegates to {@code freeswitch-ra} via {@link FreeSwitchRaBridge}.
 * Soft-fails when FS / RA is down ({@code eslLive=false}).
 */
@ApplicationScoped
public class EslClient {

    @Inject
    FreeSwitchRaBridge bridge;

    public boolean live() {
        return bridge.live();
    }

    public String api(String command) {
        return bridge.api(command);
    }

    public String bgapi(String command) {
        FreeSwitchResourceAdaptor a = bridge.adaptor();
        if (a == null) {
            return "-ERR ESL unavailable: freeswitch-ra not bound";
        }
        return a.bgapiSync(command);
    }
}
