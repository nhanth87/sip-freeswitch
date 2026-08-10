package et.elisa.ims.scscf;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.trunk.SipTrunkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** ISC routing to FreeSWITCH AS/MRF (iFC-lite). Prefers sf_sip_trunk over props. */
@ApplicationScoped
public class IscAsRouter {

    @Inject ImsRoleFlags roles;
    @Inject SipTrunkService trunks;

    @ConfigProperty(name = "sip.trunk.freeswitch-uri", defaultValue = "sip:1000@127.0.0.1:5062")
    String fallbackTrunk;

    /** Target URI for originating/terminating AS (FS). */
    public String asUri() {
        String configured = roles != null ? roles.iscAsUri() : null;
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (trunks != null) {
            try {
                String fromDb = trunks.resolveIscOrMrfUri();
                if (fromDb != null && !fromDb.isBlank()) {
                    return fromDb;
                }
            } catch (RuntimeException ex) {
                // Panache unavailable in unit tests — fall through
            }
        }
        return fallbackTrunk;
    }

    /**
     * Whether to ISC toward AS/MRF. When {@code sf.ims.feature.mmtel=false}, never force AS
     * (route Request-URI). When on: blank iFC = lab default invoke; else token match.
     */
    public boolean shouldInvokeAs(String ifcJson) {
        if (roles != null && !roles.featureMmtel()) {
            return false;
        }
        if (ifcJson == null || ifcJson.isBlank()) {
            return true; // lab default: always ISC to FS
        }
        return ifcJson.contains("\"as\"") || ifcJson.contains("MRF") || ifcJson.contains("mmtel");
    }
}
