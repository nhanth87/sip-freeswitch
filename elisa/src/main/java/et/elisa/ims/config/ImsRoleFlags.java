package et.elisa.ims.config;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * IMS Core role + lab feature flags (grill I2 + full-fledged surface).
 * Roles = which CSCF/HSS process runs here; features = protocol planes gated on top.
 */
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

    /** N–N peer S-CSCF for Mw retarget after home S stop (lab kill-test). */
    @ConfigProperty(name = "sf.ims.scscf.peer-uri", defaultValue = "sip:scscf-2:5060")
    String scscfPeerUri;

    @ConfigProperty(name = "sf.ims.icscf-uri", defaultValue = "sip:icscf.ims.lab:5060")
    String icscfUri;

    @ConfigProperty(name = "sf.ims.pcscf-uri", defaultValue = "sip:pcscf.ims.lab:5060")
    String pcscfUri;

    /** Optional: empty env ({@code SF_IMS_ISC_AS=}) must not fail boot — blank = no static AS. */
    @ConfigProperty(name = "sf.ims.isc.as-uri")
    Optional<String> iscAsUri = Optional.empty();

    /** iFC / MMTel-lite AS invoke gate ({@code IscAsRouter}). */
    @ConfigProperty(name = "sf.ims.feature.mmtel", defaultValue = "true")
    boolean featureMmtel;

    /** Master for dual-write CDRsV1 + Rf ACR + SessionS / deferred debit. */
    @ConfigProperty(name = "sf.ims.feature.charging", defaultValue = "false")
    boolean featureCharging;

    /** ENUM/NAPTR stub — off by default; no resolver until grilled. */
    @ConfigProperty(name = "sf.ims.feature.enum", defaultValue = "false")
    boolean featureEnum;

    public boolean enabled() { return enabled; }
    public boolean pcscf() { return enabled && pcscf; }
    public boolean icscf() { return enabled && icscf; }
    public boolean scscf() { return enabled && scscf; }
    public boolean hss() { return enabled && hss; }
    public boolean anyCscf() { return pcscf() || icscf() || scscf(); }
    public String realm() { return realm; }
    public String scscfUri() { return scscfUri; }
    public String scscfPeerUri() { return scscfPeerUri; }
    public String icscfUri() { return icscfUri; }
    public String pcscfUri() { return pcscfUri; }
    public String iscAsUri() { return iscAsUri.orElse(""); }

    public boolean featureMmtel() { return enabled && featureMmtel; }
    public boolean featureCharging() { return enabled && featureCharging; }
    public boolean featureEnum() { return enabled && featureEnum; }

    public String summary() {
        return "enabled=" + enabled
                + " P=" + pcscf() + " I=" + icscf() + " S=" + scscf() + " HSS=" + hss()
                + " mmtel=" + featureMmtel() + " charging=" + featureCharging()
                + " enum=" + featureEnum();
    }

    public String featureSummary() {
        return "mmtel=" + featureMmtel()
                + " charging=" + featureCharging()
                + " enum=" + featureEnum();
    }
}
