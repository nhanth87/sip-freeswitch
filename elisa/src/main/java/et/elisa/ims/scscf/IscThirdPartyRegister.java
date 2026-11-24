package et.elisa.ims.scscf;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.esl.EslClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Third-party REGISTER toward FreeSWITCH AS after S-CSCF 200 OK (lab: ESL notify / sofia status).
 *
 * <p>The ESL calls (a fresh ESL session each, 2 s read timeout, plus a sofia profile rescan) used
 * to run on the SBB event thread before the 200 OK: the UE saw its 200 ~3 s after the SAA and
 * retransmitted REGISTER meanwhile. They now run on a virtual thread after the 200 is sent, and
 * concurrent registrations coalesce into one refresh in flight plus at most one follow-up.
 */
@ApplicationScoped
public class IscThirdPartyRegister {

    private static final Logger LOG = LogManager.getLogger(IscThirdPartyRegister.class);

    @Inject ImsRoleFlags roles;
    @Inject IscAsRouter isc;
    @Inject EslClient esl;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();

    public void notifyAs(String impu, String contact, int expires) {
        if (roles == null || !roles.scscf()) {
            return;
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("ISC third-party REGISTER notify as={} impu={} contact={} expires={}",
                    isc.asUri(), impu, contact, expires);
        }
        again.set(true);
        if (running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("isc-as-refresh").start(this::drain);
        }
    }

    private void drain() {
        try {
            while (again.getAndSet(false)) {
                refreshAs();
            }
        } finally {
            running.set(false);
        }
        // A notify that raced the exit above starts the next round.
        if (again.get() && running.compareAndSet(false, true)) {
            Thread.ofVirtual().name("isc-as-refresh").start(this::drain);
        }
    }

    /** Soft path: refresh FS directory / xml_curl cache when ESL is live (lab AS notify). */
    void refreshAs() {
        try {
            if (esl.live()) {
                esl.api("xml_flush_cache");
                // Hint sofia reload profile so AS sees new contact bindings sooner
                esl.bgapi("sofia profile internal rescan");
            }
        } catch (RuntimeException e) {
            LOG.warn("ISC AS refresh failed: {}", e.toString());
        }
    }
}
