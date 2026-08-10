package et.elisa.ims.charging;

import et.elisa.ims.hss.HssLiteService;
import et.elisa.persist.ImsSubscriber;
import et.elisa.persist.SfCdr;
import et.elisa.profile.ElisaProfiles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * CGRates Account = msisdn (IMS profile SoT); null → soft impi (grill G4).
 */
@ApplicationScoped
public class ChargingAccountResolver {

    @Inject HssLiteService hss;
    @Inject CgratesConfig config;

    public ChargingAccountResolver() {}

    public ChargingAccountResolver(HssLiteService hss, CgratesConfig config) {
        this.hss = hss;
        this.config = config;
    }

    public String resolveAccount(SfCdr row) {
        if (row == null) {
            return null;
        }
        String msisdn = blankToNull(row.msisdn);
        String impi = null;
        if (msisdn == null && hss != null) {
            Optional<ImsSubscriber> sub = Optional.empty();
            if (row.sender != null && !row.sender.isBlank()) {
                sub = hss.resolveIdentity(row.sender);
            }
            if (sub.isEmpty() && row.recipient != null && !row.recipient.isBlank()) {
                sub = hss.resolveIdentity(row.recipient);
            }
            if (sub.isPresent()) {
                msisdn = blankToNull(sub.get().msisdn);
                impi = blankToNull(sub.get().impi);
            }
        }
        String prefer = config == null ? "msisdn" : config.accountKey();
        if ("impi".equalsIgnoreCase(prefer) && impi != null) {
            return impi;
        }
        return ElisaProfiles.subscriberKey(msisdn, impi);
    }

    /** Pure resolve without HSS (unit tests / when CDR already enriched). */
    public static String resolve(String msisdn, String impi) {
        return ElisaProfiles.subscriberKey(msisdn, impi);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
