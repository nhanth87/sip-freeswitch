package et.epc.hss;

import et.epc.api.Imsi;
import et.epc.api.SubscriberProfile;
import java.util.concurrent.atomic.AtomicLong;

/** Durable HSS row. Ki/OPc live in {@link SecretStore}, never on this object. */
public final class SubscriberRecord {
    private final SubscriberRecordId id;
    private final SubscriberProfile profile;
    private final AtomicLong sqn;
    private volatile String servingMmeHost;
    private volatile boolean purged;
    private volatile String imsScscfUri;

    public SubscriberRecord(SubscriberProfile profile, long sqn) {
        this.profile = profile;
        this.id = new SubscriberRecordId(profile.imsi());
        this.sqn = new AtomicLong(sqn);
    }

    public Imsi imsi() {
        return profile.imsi();
    }

    public SubscriberProfile profile() {
        return profile;
    }

    public SubscriberRecordId id() {
        return id;
    }

    public long sqn() {
        return sqn.get();
    }

    /** Atomically reserve next SQN used in AUTN. */
    public long reserveSqn() {
        return sqn.incrementAndGet();
    }

    public void forceSqn(long next) {
        sqn.updateAndGet(cur -> Math.max(cur, next));
    }

    public String servingMmeHost() {
        return servingMmeHost;
    }

    public void setServingMmeHost(String host) {
        this.servingMmeHost = host;
    }

    public boolean purged() {
        return purged;
    }

    public void setPurged(boolean p) {
        this.purged = p;
    }

    public String imsScscfUri() {
        return imsScscfUri;
    }

    public void setImsScscfUri(String uri) {
        this.imsScscfUri = uri;
    }
}
