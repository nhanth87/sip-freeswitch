package et.sipfreeswitch.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Lab registrar for softphones — not on the 10k CPS hot path. */
public final class RegistrationSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(RegistrationSbb.class);
    private final ConcurrentMap<String, List<String>> registrations = new ConcurrentHashMap<>();

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public RegistrationSbb() {
    }

    @Override
    public void sbbRemove() {
    }

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (event instanceof SipRegisterEvent reg) {
            onSipRegisterEvent(reg);
        }
    }

    void onSipRegisterEvent(SipRegisterEvent event) {
        String aor = event.toUri();
        String contact = event.contactUri();
        int expires = event.expires();
        LOG.debug("[RegistrationSbb] REGISTER aor={} expires={}", aor, expires);

        if (expires == 0) {
            registrations.computeIfPresent(aor, (k, contacts) -> {
                contacts.remove(contact);
                return contacts.isEmpty() ? null : contacts;
            });
        } else {
            registrations.merge(aor, new ArrayList<>(List.of(contact)), (old, nu) -> {
                if (!old.contains(contact)) {
                    old.add(contact);
                }
                return old;
            });
        }

        RaCommandPort port = this.sipRa;
        if (port != null) {
            port.sendCommand(new SendResponse(event.callId(), 200, "OK"));
        }
    }

    public List<String> lookup(String aor) {
        return registrations.getOrDefault(aor, List.of());
    }
}
