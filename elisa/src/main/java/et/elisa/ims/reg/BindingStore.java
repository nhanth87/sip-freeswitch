package et.elisa.ims.reg;

import et.elisa.cluster.RegistrationHomeStore;
import et.elisa.persist.ImsBinding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class BindingStore {

    @Inject RegistrationHomeStore regHome;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String nodeId;

    @Transactional
    public void upsert(String impu, String contact, String path, String callId, int expiresSec) {
        Instant exp = Instant.now().plusSeconds(Math.max(0, expiresSec));
        ImsBinding b = ImsBinding.find("impu = ?1 and contact = ?2", impu, contact).firstResult();
        if (expiresSec == 0) {
            if (b != null) {
                b.delete();
            }
            regHome.remove(impu);
            return;
        }
        if (b == null) {
            b = new ImsBinding();
            b.impu = impu;
            b.contact = contact;
        }
        b.pathHeader = path;
        b.homeScscfNode = nodeId;
        b.expiresAt = exp;
        b.callId = callId;
        b.updatedAt = Instant.now();
        b.persist();
        regHome.put(impu, nodeId);
    }

    @Transactional
    public Optional<ImsBinding> findLive(String impu) {
        List<ImsBinding> all = ImsBinding.list("impu", impu);
        Instant now = Instant.now();
        return all.stream().filter(b -> b.expiresAt.isAfter(now)).findFirst();
    }

    /** Remove expired bindings (re-REGISTER timer / Chronos-lite). */
    @Transactional
    public int purgeExpired() {
        Instant now = Instant.now();
        List<ImsBinding> expired = ImsBinding.list("expiresAt < ?1", now);
        int n = 0;
        for (ImsBinding b : expired) {
            String impu = b.impu;
            b.delete();
            n++;
            // Drop home sticky when no live binding remains for IMPU.
            List<ImsBinding> remain = ImsBinding.list("impu", impu);
            boolean anyLive = remain.stream().anyMatch(x -> x.expiresAt.isAfter(now));
            if (!anyLive) {
                regHome.remove(impu);
            }
        }
        return n;
    }

    @Transactional
    public List<ImsBinding> listAll() {
        return ImsBinding.listAll();
    }

    public Optional<String> homeNode(String impu) {
        return regHome.get(impu).or(() -> findLive(impu).map(b -> b.homeScscfNode));
    }

    public String nodeId() {
        return nodeId;
    }
}
