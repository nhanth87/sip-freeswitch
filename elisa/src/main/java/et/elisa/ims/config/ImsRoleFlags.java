package et.elisa.ims.config;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Feature flags for combined IMS Core roles (grill I2). */
@ApplicationScoped
public class ImsRoleFlags {

    @ConfigProperty(name = "sf.ims.role.pcscf", defaultValue = "true")
    boolean pcscf;

    @ConfigProperty(name = "sf.ims.role.icscf", defaultValue = "true")
    boolean icscf;

    @ConfigProperty(name = "sf.ims.role.scscf", defaultValue = "true")
    boolean scscf;

    @ConfigProperty(name = "sf.ims.role.hss", defaultValue = "true")
    boolean hss;

    @ConfigProperty(name = "sf.ims.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.ims.realm", defaultValue = "ims.lab")
    String realm;

    @ConfigProperty(name = "sf.ims.scscf-uri", defaultValue = "sip:scscf.ims.lab:5060")
    String scscfUri;

    @ConfigProperty(name = "sf.ims.icscf-uri", defaultValue = "sip:icscf.ims.lab:5060")
    String icscfUri;

    @ConfigProperty(name = "sf.ims.pcscf-uri", defaultValue = "sip:pcscf.ims.lab:5060")
    String pcscfUri;

    @ConfigProperty(name = "sf.ims.isc.as-uri", defaultValue = "")
    String iscAsUri;

    public boolean enabled() { return enabled; }
    public boolean pcscf() { return enabled && pcscf; }
    public boolean icscf() { return enabled && icscf; }
    public boolean scscf() { return enabled && scscf; }
    public boolean hss() { return enabled && hss; }
    public boolean anyCscf() { return pcscf() || icscf() || scscf(); }
    public String realm() { return realm; }
    public String scscfUri() { return scscfUri; }
    public String icscfUri() { return icscfUri; }
    public String pcscfUri() { return pcscfUri; }
    public String iscAsUri() { return iscAsUri; }

    public String summary() {
        return "enabled=" + enabled
                + " P=" + pcscf() + " I=" + icscf() + " S=" + scscf() + " HSS=" + hss();
    }
}
