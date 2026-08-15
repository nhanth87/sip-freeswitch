package et.elisa.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.core.MicroSleeContainer;
import et.elisa.persist.ImsSubscriber;
import java.time.Instant;
import java.util.Collection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** In-memory ProfileFacility upsert / index tests — no Quarkus. */
class ElisaProfilesTest {

    private MicroSleeContainer container;
    private ProfileFacility pf;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        pf = container.getProfileFacility();
        ElisaProfiles.provision(pf);
    }

    @AfterEach
    void tearDown() {
        if (container != null && container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    @Test
    void upsertSubscriber_byMsisdn_andFindByImpi() {
        ImsSubscriber row = new ImsSubscriber();
        row.msisdn = "251911000001";
        row.impi = "251911000001@ims.lab";
        row.impu = "sip:251911000001@ims.lab";
        row.tenantId = "t1";
        row.displayName = "Lab UE";
        row.fsA1Hash = "abc";
        row.enabled = true;
        row.updatedAt = Instant.now();

        ImsSubscriberProfile sub = ElisaProfiles.upsertSubscriber(pf, row).orElseThrow();
        assertEquals("251911000001", sub.getMsisdn());
        assertEquals("true", sub.getEnabled());
        assertEquals("251911000001@ims.lab", sub.getImpi());

        Collection<ProfileLocalObject> hits =
                pf.findProfilesByAttribute(ImsSubscriberProfile.TABLE, "impi", row.impi);
        assertEquals(1, hits.size());
        assertEquals("251911000001",
                ((ImsSubscriberProfile) hits.iterator().next().getProfile()).getMsisdn());
    }

    @Test
    void upsertSubscriber_fallsBackToImpiWhenNoMsisdn() {
        ImsSubscriber row = new ImsSubscriber();
        row.impi = "user@ims.lab";
        row.impu = "sip:user@ims.lab";
        row.enabled = false;
        row.updatedAt = Instant.now();

        ImsSubscriberProfile sub = ElisaProfiles.upsertSubscriber(pf, row).orElseThrow();
        assertEquals("user@ims.lab", ElisaProfiles.subscriberKey(row));
        assertEquals("false", sub.getEnabled());
        assertEquals("user", sub.getDirectoryUserId());
        assertEquals(1, ElisaProfiles.findByDirectoryUserId(pf, "user").size());
        assertNotNull(pf.getProfile(new ProfileID(ImsSubscriberProfile.TABLE, "user@ims.lab")));
    }

    @Test
    void callSession_upsertIndexAndRemove() {
        long now = System.currentTimeMillis();
        CallSessionProfile cs = ElisaProfiles.upsertCallSession(
                pf, "fs-uuid-1", "sip-call-abc", "251911", "t1",
                "elisa-1", "fs-a", "inbound", "CREATED", now, now).orElseThrow();
        assertEquals("fs-uuid-1", cs.getFsUuid());
        assertEquals("CREATED", cs.getState());

        Collection<ProfileLocalObject> bySip =
                ElisaProfiles.findBySipCallId(pf, "sip-call-abc");
        assertEquals(1, bySip.size());
        ElisaProfiles.upsertCallSession(
                pf, "fs-uuid-enc", "ab10%40127.0.0.1", "alice", null,
                "elisa-1", "fs-a", "inbound", "ANSWERED", now, now);
        assertEquals(1, ElisaProfiles.findBySipCallId(pf, "ab10@127.0.0.1").size());

        ElisaProfiles.endCallSession(pf, "fs-uuid-1", "HANGUP");
        assertFalse(pf.profileExists(new ProfileID(CallSessionProfile.TABLE, "fs-uuid-1")));
    }

    @Test
    void upsertCallSession_isIdempotent() {
        long t0 = 1_000L;
        ElisaProfiles.upsertCallSession(
                pf, "u1", "c1", "m1", null, "n1", "f1", "in", "CREATED", t0, t0);
        ElisaProfiles.upsertCallSession(
                pf, "u1", "c1", "m1", null, "n1", "f1", "in", "ANSWERED", t0, t0 + 5);
        CallSessionProfile cs = (CallSessionProfile) pf
                .getProfile(new ProfileID(CallSessionProfile.TABLE, "u1"))
                .getProfile();
        assertEquals("ANSWERED", cs.getState());
        assertEquals(t0, cs.getStartedAtEpochMs());
        assertTrue(cs.getUpdatedAtEpochMs() >= t0);
    }
}
