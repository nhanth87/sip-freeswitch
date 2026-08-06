package et.elisa.sbbs;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.sipservlet.command.SendResponse;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import et.elisa.acl.SipIngressAclService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Lab registrar — REGISTER from unapproved Contact host → 403. */
public final class RegistrationSbb implements Sbb, SleeEventHandler {

    private static final Logger LOG = LogManager.getLogger(RegistrationSbb.class);
    private final ConcurrentMap<String, List<String>> registrations = new ConcurrentHashMap<>();
    private final SipIngressAclService acl;

    @InjectRa(name = "sip-servlet-ra")
    private volatile RaCommandPort sipRa;

    public RegistrationSbb() {
        this(null);
    }

    public RegistrationSbb(SipIngressAclService acl) {
        this.acl = acl;
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
        String peer = SipIngressAclService.peerFromContactUri(event.contactUri());
        if (acl != null && !acl.isAllowed(peer)) {
            LOG.warn("[RegistrationSbb] REGISTER blocked by ACL peer={} aor={}", peer, event.toUri());
            RaCommandPort port = this.sipRa;
            if (port != null) {
                port.sendCommand(new SendResponse(event.callId(), 403, "Forbidden"));
            }
            return;
        }

        String aor = event.toUri();
        String contact = event.contactUri();
        int expires = event.expires();
        LOG.debug("[RegistrationSbb] REGISTER aor={} peer={} expires={}", aor, peer, expires);

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
