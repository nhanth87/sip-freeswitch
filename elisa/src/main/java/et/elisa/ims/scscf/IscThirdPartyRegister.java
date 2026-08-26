package et.elisa.ims.scscf;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.esl.EslClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Third-party REGISTER toward FreeSWITCH AS after S-CSCF 200 OK (lab: ESL notify / sofia status).
 */
@ApplicationScoped
public class IscThirdPartyRegister {

    private static final Logger LOG = LogManager.getLogger(IscThirdPartyRegister.class);

    @Inject ImsRoleFlags roles;
    @Inject IscAsRouter isc;
    @Inject EslClient esl;

    public void notifyAs(String impu, String contact, int expires) {
        if (roles == null || !roles.scscf()) {
            return;
        }
        String as = isc.asUri();
        LOG.info("ISC third-party REGISTER notify as=%s impu=%s contact=%s expires=%d",
                as, impu, contact, expires);
        // Soft path: refresh FS directory/xml_curl cache when ESL live (lab AS notify)
        if (esl.live()) {
            esl.api("xml_flush_cache");
            // Hint sofia reload profile so AS sees new contact bindings sooner
            esl.bgapi("sofia profile internal rescan");
        }
    }
}
