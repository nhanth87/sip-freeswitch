package et.elisa.profile;

import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import et.elisa.cmp.SipCallIds;
import et.elisa.persist.ImsSubscriber;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Pure ProfileFacility helpers for Elisa ProfileSpecifications.
 * Prefer {@link com.microjainslee.core.MicroSleeContainer#getProfileFacility()} —
 * not the legacy Quarkus {@code ProfileTablePort} stub.
 *
 * <p><b>N–N footgun (do not repeat USSDGW ussdUser):</b> default ProfileFacility is
 * JVM-local ({@code InMemoryProfileFacility}). Live INVITE / mid-dialog identity for
 * cluster restore is <em>not</em> Profile — it is {@code DialogCmpPersistence}
 * (ISPN DIST_SYNC → PG {@code sf_dialog_checkpoint}). {@link CallSessionProfile} is a
 * hot mirror / admin convenience; never the sole SoT for failover.
 */
public final class ElisaProfiles {

    private static final Logger LOG = LogManager.getLogger(ElisaProfiles.class);

    private ElisaProfiles() {}

    /** Idempotent table + secondary index provisioning (call before traffic). */
    public static void provision(ProfileFacility pf) {
        if (pf == null) {
            return;
        }
        pf.createProfileTable(ImsSubscriberProfile.TABLE);
        pf.registerIndex(ImsSubscriberProfile.TABLE, "impi");
        pf.registerIndex(ImsSubscriberProfile.TABLE, "impu");
        pf.registerIndex(ImsSubscriberProfile.TABLE, "directoryUserId");
        pf.registerIndex(ImsSubscriberProfile.TABLE, "tenantId");

        pf.createProfileTable(CallSessionProfile.TABLE);
        pf.registerIndex(CallSessionProfile.TABLE, "sipCallId");
        pf.registerIndex(CallSessionProfile.TABLE, "msisdn");

        pf.createProfileTable(MvnoTenantProfile.TABLE);
        LOG.info("SLEE Profile tables ready: {} + {} + {}", ImsSubscriberProfile.TABLE,
                CallSessionProfile.TABLE, MvnoTenantProfile.TABLE);
    }

    /** Profile PK: MSISDN preferred, else IMPI (JP2). */
    public static String subscriberKey(ImsSubscriber row) {
        if (row == null) {
            return null;
        }
        if (row.msisdn != null && !row.msisdn.isBlank()) {
            return row.msisdn.trim();
        }
        return row.impi == null || row.impi.isBlank() ? null : row.impi.trim();
    }

    public static String subscriberKey(String msisdn, String impi) {
        if (msisdn != null && !msisdn.isBlank()) {
            return msisdn.trim();
        }
        return impi == null || impi.isBlank() ? null : impi.trim();
    }

    /** Upsert non-crypto identity fields from PG {@code ims_subscriber}. */
    public static Optional<ImsSubscriberProfile> upsertSubscriber(ProfileFacility pf, ImsSubscriber row) {
        if (pf == null || row == null) {
            return Optional.empty();
        }
        String key = subscriberKey(row);
        if (key == null) {
            return Optional.empty();
        }
        try {
            ImsSubscriberProfile sub = getOrCreate(pf, ImsSubscriberProfile.TABLE, key, ImsSubscriberProfile.class);
            sub.setMsisdn(row.msisdn);
            sub.setImpi(row.impi);
            sub.setImpu(row.impu);
            sub.setDirectoryUserId(row.directoryUserId());
            sub.setTenantId(row.tenantId);
            sub.setDisplayName(row.displayName);
            sub.setFsA1Hash(row.fsA1Hash);
            sub.setEnabled(row.enabled ? "true" : "false");
            long now = row.updatedAt != null ? row.updatedAt.toEpochMilli() : System.currentTimeMillis();
            sub.setUpdatedAtEpochMs(now);
            return Optional.of(sub);
        } catch (RuntimeException e) {
            LOG.warn("ImsSubscriberProfile upsert failed key={}: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public static Optional<CallSessionProfile> upsertCallSession(
            ProfileFacility pf,
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
        if (pf == null || fsUuid == null || fsUuid.isBlank()) {
            return Optional.empty();
        }
        try {
            CallSessionProfile cs = getOrCreate(pf, CallSessionProfile.TABLE, fsUuid, CallSessionProfile.class);
            cs.setFsUuid(fsUuid);
            if (sipCallId != null) {
                cs.setSipCallId(SipCallIds.canonical(sipCallId));
            }
            if (msisdn != null) {
                cs.setMsisdn(msisdn);
            }
            if (tenantId != null) {
                cs.setTenantId(tenantId);
            }
            if (elisaNodeId != null) {
                cs.setElisaNodeId(elisaNodeId);
            }
            if (fsNodeId != null) {
                cs.setFsNodeId(fsNodeId);
            }
            if (direction != null) {
                cs.setDirection(direction);
            }
            cs.setState(state == null ? "CREATED" : state);
            if (cs.getStartedAtEpochMs() == null) {
                cs.setStartedAtEpochMs(startedAtEpochMs);
            }
            cs.setUpdatedAtEpochMs(updatedAtEpochMs);
            return Optional.of(cs);
        } catch (RuntimeException e) {
            LOG.warn("CallSessionProfile upsert failed fsUuid={}: {}", fsUuid, e.toString());
            return Optional.empty();
        }
    }

    /** Set HANGUP then remove the live CallSession profile row. */
    public static void endCallSession(ProfileFacility pf, String fsUuid, String hangupState) {
        if (pf == null || fsUuid == null || fsUuid.isBlank()) {
            return;
        }
        try {
            ProfileLocalObject plo = pf.getProfile(new ProfileID(CallSessionProfile.TABLE, fsUuid));
            if (plo != null && plo.getProfile() instanceof CallSessionProfile cs) {
                cs.setState(hangupState == null ? "HANGUP" : hangupState);
                cs.setUpdatedAtEpochMs(System.currentTimeMillis());
            }
            pf.removeProfile(new ProfileID(CallSessionProfile.TABLE, fsUuid));
        } catch (RuntimeException e) {
            LOG.warn("CallSessionProfile remove failed fsUuid={}: {}", fsUuid, e.toString());
        }
    }

    public static Collection<ProfileLocalObject> findBySipCallId(ProfileFacility pf, String sipCallId) {
        if (pf == null || sipCallId == null || sipCallId.isBlank()) {
            return List.of();
        }
        String canon = SipCallIds.canonical(sipCallId);
        Collection<ProfileLocalObject> hits =
                pf.findProfilesByAttribute(CallSessionProfile.TABLE, "sipCallId", canon);
        if (!hits.isEmpty() || canon.equals(sipCallId)) {
            return hits;
        }
        return pf.findProfilesByAttribute(CallSessionProfile.TABLE, "sipCallId", sipCallId);
    }

    public static Collection<ProfileLocalObject> findByDirectoryUserId(ProfileFacility pf, String directoryUserId) {
        if (pf == null || directoryUserId == null || directoryUserId.isBlank()) {
            return List.of();
        }
        return pf.findProfilesByAttribute(ImsSubscriberProfile.TABLE, "directoryUserId", directoryUserId.trim());
    }

    /** Mirror one {@code sf_tenant} row into the MvnoTenant hot store (PK = tenantId). */
    public static Optional<MvnoTenantProfile> upsertTenant(ProfileFacility pf, et.elisa.persist.SfTenant row) {
        if (pf == null || row == null || row.id == null) {
            return Optional.empty();
        }
        try {
            MvnoTenantProfile t = getOrCreate(pf, MvnoTenantProfile.TABLE, row.id, MvnoTenantProfile.class);
            t.setTenantId(row.id);
            t.setName(row.name);
            t.setEnabled(row.enabled ? "true" : "false");
            t.setMaxCps((long) row.maxCps);
            t.setMsisdnPrefix(row.msisdnPrefix);
            t.setIscAsUri(row.iscAsUri);
            t.setUpdatedAtEpochMs(row.updatedAt != null ? row.updatedAt.toEpochMilli() : System.currentTimeMillis());
            return Optional.of(t);
        } catch (RuntimeException e) {
            LOG.warn("MvnoTenantProfile upsert failed tenant={}: {}", row.id, e.toString());
            return Optional.empty();
        }
    }

    public static Optional<MvnoTenantProfile> getTenant(ProfileFacility pf, String tenantId) {
        if (pf == null || tenantId == null) {
            return Optional.empty();
        }
        ProfileLocalObject plo = pf.getProfile(new ProfileID(MvnoTenantProfile.TABLE, tenantId));
        if (plo != null && plo.getProfile() instanceof MvnoTenantProfile t) {
            return Optional.of(t);
        }
        return Optional.empty();
    }

    public static Optional<ImsSubscriberProfile> getSubscriber(ProfileFacility pf, String profileKey) {
        if (pf == null || profileKey == null) {
            return Optional.empty();
        }
        ProfileLocalObject plo = pf.getProfile(new ProfileID(ImsSubscriberProfile.TABLE, profileKey));
        if (plo != null && plo.getProfile() instanceof ImsSubscriberProfile sub) {
            return Optional.of(sub);
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
