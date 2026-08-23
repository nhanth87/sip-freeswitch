package et.epc.hss;

import et.epc.api.Imsi;
import et.epc.api.SubscriberProfile;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Durable HSS row. Ki/OPc live in {@link SecretStore}, never on this object. */
public final class SubscriberRecord {
    private final SubscriberRecordId id;
    private final SubscriberProfile profile;
    private final AtomicLong sqn;
    private volatile String servingMmeHost;
    private volatile boolean purged;
    private volatile String imsScscfUri;
    private volatile String impi;
    private volatile List<String> impus = List.of();
    private volatile String serviceProfileJson;
    /** IMPU registration state (TS 23.002): NOT_REGISTERED / REGISTERED / UNREGISTERED. */
    private volatile String impuRegState = "NOT_REGISTERED";
    /** S-CSCF URI stored alongside the IMPU registration state (Cx SAR/RTR). */
    private volatile String scscfUri;

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

    /** IMS private identity (IMPI). No secret material on this object. */
    public String impi() {
        return impi;
    }

    public void setImpi(String impi) {
        this.impi = impi;
    }

    /** IMS public identities (IMPU list). Returns an immutable snapshot. */
    public List<String> impus() {
        return impus;
    }

    public void setImpus(List<String> impus) {
        this.impus = List.copyOf(impus == null ? List.of() : impus);
    }

    /** Serialized service profile / initial filter criteria (iFC), JSON or other. */
    public String serviceProfileJson() {
        return serviceProfileJson;
    }

    public void setServiceProfileJson(String serviceProfileJson) {
        this.serviceProfileJson = serviceProfileJson;
    }

    /** IMPU registration state (TS 23.002): NOT_REGISTERED / REGISTERED / UNREGISTERED. */
    public String impuRegState() {
        return impuRegState;
    }

    public void setImpuRegState(String state) {
        this.impuRegState = (state == null || state.isBlank()) ? "NOT_REGISTERED" : state.trim();
    }

    /** S-CSCF URI stored alongside the IMPU registration state (Cx SAR/RTR). */
    public String scscfUri() {
        return scscfUri;
    }

    public void setScscfUri(String uri) {
        this.scscfUri = uri;
    }
}
