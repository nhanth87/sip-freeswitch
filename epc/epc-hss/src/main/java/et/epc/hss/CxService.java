package et.epc.hss;

import et.epc.api.Imsi;
import java.util.Objects;

/**
 * Production Cx face of the same subscriber repository. IMS S-CSCF registration
 * is stored separately from the EPS serving-MME field.
 */
public final class CxService {

    private final HssRepository repo;

    public CxService(HssRepository repo) {
        this.repo = Objects.requireNonNull(repo);
    }

    public String assignScscf(Imsi imsi, String scscfUri) {
        repo.setImsScscf(imsi, scscfUri);
        return repo.find(imsi).map(SubscriberRecord::imsScscfUri).orElse(scscfUri);
    }

    public String locatedScscf(Imsi imsi) {
        return repo.find(imsi).map(SubscriberRecord::imsScscfUri).orElse(null);
    }
}
