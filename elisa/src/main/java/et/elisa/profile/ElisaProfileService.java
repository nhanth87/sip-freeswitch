package et.elisa.profile;

import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.core.MicroSleeContainer;
import et.elisa.persist.ImsSubscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Collection;
import java.util.Optional;

/**
 * CDI façade over the container {@link ProfileFacility} (JSR-240 §10).
 * Do not inject the legacy Quarkus {@code ProfileTablePort} bean — it is a
 * separate stub without {@code registerIndex} / typed {@code createProfile}.
 */
@ApplicationScoped
public class ElisaProfileService {

    @Inject MicroSleeContainer container;

    /** Package / test constructor. */
    public ElisaProfileService() {}

    public ElisaProfileService(MicroSleeContainer container) {
        this.container = container;
    }

    public ProfileFacility facility() {
        return container == null ? null : container.getProfileFacility();
    }

    public void provisionTables() {
        ElisaProfiles.provision(facility());
    }

    public Optional<ImsSubscriberProfile> upsertSubscriber(ImsSubscriber row) {
        return ElisaProfiles.upsertSubscriber(facility(), row);
    }

    /** Boot-time warm: 10k users is one pass, never per INVITE. */
    public int warmSubscribers(Iterable<ImsSubscriber> rows) {
        if (rows == null) {
            return 0;
        }
        int n = 0;
        for (ImsSubscriber row : rows) {
            if (upsertSubscriber(row).isPresent()) {
                n++;
            }
        }
        return n;
    }

    public Optional<MvnoTenantProfile> upsertTenant(et.elisa.persist.SfTenant row) {
        return ElisaProfiles.upsertTenant(facility(), row);
    }

    public Optional<MvnoTenantProfile> getTenant(String tenantId) {
        return ElisaProfiles.getTenant(facility(), tenantId);
    }

    /** Boot-time warm of every tenant's service config (never per INVITE). */
    public int warmTenants(Iterable<et.elisa.persist.SfTenant> rows) {
        int n = 0;
        if (rows != null) {
            for (et.elisa.persist.SfTenant row : rows) {
                if (upsertTenant(row).isPresent()) {
                    n++;
                }
            }
        }
        return n;
    }

    public Optional<ImsSubscriberProfile> getSubscriber(String profileKey) {
        return ElisaProfiles.getSubscriber(facility(), profileKey);
    }

    public Optional<ImsSubscriberProfile> findByDirectoryUserId(String directoryUserId) {
        Collection<ProfileLocalObject> hits = ElisaProfiles.findByDirectoryUserId(facility(), directoryUserId);
        for (ProfileLocalObject plo : hits) {
            if (plo.getProfile() instanceof ImsSubscriberProfile sub) {
                return Optional.of(sub);
            }
        }
        return Optional.empty();
    }

    public Optional<CallSessionProfile> upsertCallSession(
            String fsUuid,
            String sipCallId,
            String msisdn,
            String tenantId,
            String elisaNodeId,
            String fsNodeId,
            String direction,
            String state,
            long startedAtEpochMs,
            long updatedAtEpochMs) {
        return ElisaProfiles.upsertCallSession(
                facility(), fsUuid, sipCallId, msisdn, tenantId,
                elisaNodeId, fsNodeId, direction, state,
                startedAtEpochMs, updatedAtEpochMs);
    }

    public void endCallSession(String fsUuid, String hangupState) {
        ElisaProfiles.endCallSession(facility(), fsUuid, hangupState);
    }
}
