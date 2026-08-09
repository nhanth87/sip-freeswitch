package et.elisa.profile;

import com.microjainslee.api.ProfileFacility;
import com.microjainslee.core.MicroSleeContainer;
import et.elisa.persist.ImsSubscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
