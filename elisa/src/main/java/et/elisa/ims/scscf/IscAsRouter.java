package et.elisa.ims.scscf;

import et.elisa.ims.config.ImsRoleFlags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** ISC routing to FreeSWITCH AS/MRF (iFC-lite). */
@ApplicationScoped
public class IscAsRouter {

    @Inject ImsRoleFlags roles;

    @ConfigProperty(name = "sip.trunk.freeswitch-uri", defaultValue = "sip:1000@127.0.0.1:5062")
    String fallbackTrunk;

    /** Target URI for originating/terminating AS (FS). */
    public String asUri() {
        String configured = roles.iscAsUri();
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return fallbackTrunk;
    }

    public boolean shouldInvokeAs(String ifcJson) {
        if (ifcJson == null || ifcJson.isBlank()) {
            return true; // lab default: always ISC to FS
        }
        return ifcJson.contains("\"as\"") || ifcJson.contains("MRF") || ifcJson.contains("mmtel");
    }
}
