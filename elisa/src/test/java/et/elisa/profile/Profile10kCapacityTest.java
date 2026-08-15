package et.elisa.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.junit.jupiter.api.Timeout;

/** 10k provisioned users on one JVM ProfileFacility (indexed, not listAll). */
class Profile10kCapacityTest {

    private static final int N = 10_000;

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
    @Timeout(30)
    void tenThousandSubscriberProfilesIndexedLookup() {
        Instant now = Instant.now();
        for (int i = 0; i < N; i++) {
            ImsSubscriber row = new ImsSubscriber();
            String user = "u" + i;
            row.impi = user + "@ims.lab";
            row.impu = "sip:" + user + "@ims.lab";
            row.enabled = true;
            row.updatedAt = now;
            assertTrue(ElisaProfiles.upsertSubscriber(pf, row).isPresent());
        }

        ImsSubscriberProfile mid = ElisaProfiles.getSubscriber(pf, "u5000@ims.lab").orElseThrow();
        assertEquals("sip:u5000@ims.lab", mid.getImpu());
        assertEquals("u5000", mid.getDirectoryUserId());

        Collection<ProfileLocalObject> byDir = ElisaProfiles.findByDirectoryUserId(pf, "u5000");
        assertEquals(1, byDir.size());
        Collection<ProfileLocalObject> byImpu =
                pf.findProfilesByAttribute(ImsSubscriberProfile.TABLE, "impu", "sip:u9999@ims.lab");
        assertEquals(1, byImpu.size());

        assertTrue(pf.profileExists(new ProfileID(ImsSubscriberProfile.TABLE, "u0@ims.lab")));
        assertTrue(pf.profileExists(new ProfileID(ImsSubscriberProfile.TABLE, "u9999@ims.lab")));
    }

    @Test
    @Timeout(30)
    void tenThousandCallSessionProfilesCanonicalSipIndexAndEvict() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            String sip = "c-" + i + "@127.0.0.1";
            assertTrue(ElisaProfiles.upsertCallSession(
                    pf, "fs-" + i, sip, "u" + i, null,
                    "elisa-1", "fs-1", "inbound", "ANSWERED", now, now).isPresent());
        }
        assertEquals(1, ElisaProfiles.findBySipCallId(pf, "c-5000%40127.0.0.1").size());
        ElisaProfiles.endCallSession(pf, "fs-5000", "HANGUP");
        assertEquals(0, ElisaProfiles.findBySipCallId(pf, "c-5000@127.0.0.1").size());
        assertTrue(pf.profileExists(new ProfileID(CallSessionProfile.TABLE, "fs-0")));
        assertTrue(pf.profileExists(new ProfileID(CallSessionProfile.TABLE, "fs-9999")));
    }
}
