package et.epc.profile;

import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provisioned EPC Profile tables only. Bearer / charging / peer liveness are
 * not Profile rows — those live in the session checkpoint (ISPN best-effort + PG).
 */
public final class EpcProfiles {

    private static final Logger LOG = LogManager.getLogger(EpcProfiles.class);

    private EpcProfiles() {}

    public static void provision(ProfileFacility pf) {
        if (pf == null) {
            return;
        }
        pf.createProfileTable(EpsSubscriberProfile.TABLE);
        pf.registerIndex(EpsSubscriberProfile.TABLE, "imsi");
        pf.registerIndex(EpsSubscriberProfile.TABLE, "msisdn");
        pf.createProfileTable(ApnProfile.TABLE);
        pf.registerIndex(ApnProfile.TABLE, "apn");
        LOG.info("EPC Profile tables ready: {} + {}", EpsSubscriberProfile.TABLE, ApnProfile.TABLE);
    }

    public static Optional<EpsSubscriberProfile> upsertSubscriber(
            ProfileFacility pf, String imsi, String msisdn, String defaultApn, boolean enabled) {
        if (pf == null || imsi == null || imsi.isBlank()) {
            return Optional.empty();
        }
        EpsSubscriberProfile row = getOrCreate(pf, EpsSubscriberProfile.TABLE, imsi, EpsSubscriberProfile.class);
        row.setImsi(imsi);
        row.setMsisdn(msisdn);
        row.setDefaultApn(defaultApn);
        row.setEnabled(Boolean.toString(enabled));
        return Optional.of(row);
    }

    public static Optional<ApnProfile> upsertApn(
            ProfileFacility pf, String apn, String pdnType, String ipv4Pool) {
        if (pf == null || apn == null || apn.isBlank()) {
            return Optional.empty();
        }
        ApnProfile row = getOrCreate(pf, ApnProfile.TABLE, apn, ApnProfile.class);
        row.setApn(apn);
        row.setPdnType(pdnType);
        row.setIpv4Pool(ipv4Pool);
        return Optional.of(row);
    }

    public static Optional<ApnProfile> findApn(ProfileFacility pf, String apn) {
        if (pf == null || apn == null || apn.isBlank()) {
            return Optional.empty();
        }
        ProfileLocalObject existing = pf.getProfile(new ProfileID(ApnProfile.TABLE, apn));
        if (existing != null && existing.getProfile() instanceof ApnProfile row) {
            return Optional.of(row);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <T> T getOrCreate(ProfileFacility pf, String table, String name, Class<T> type) {
        ProfileLocalObject existing = pf.getProfile(new ProfileID(table, name));
        if (existing != null && type.isInstance(existing.getProfile())) {
            return (T) existing.getProfile();
        }
        try {
            ProfileLocalObject created = pf.createProfile(table, name, (Class) type);
            return (T) created.getProfile();
        } catch (ProfileAlreadyExistsException race) {
            ProfileLocalObject again = pf.getProfile(new ProfileID(table, name));
            if (again != null && type.isInstance(again.getProfile())) {
                return (T) again.getProfile();
            }
            throw race;
        }
    }
}
