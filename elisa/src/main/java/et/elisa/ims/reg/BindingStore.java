package et.elisa.ims.reg;

import et.elisa.cluster.RegistrationHomeStore;
import et.elisa.ims.hss.ImsIdentityKeys;
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
        if (expiresSec == 0) {
            ImsBinding b = ImsBinding.find("impu = ?1 and contact = ?2", impu, contact).firstResult();
            if (b != null) {
                b.delete();
            }
            List<ImsBinding> remain = ImsBinding.list("impu", impu);
            if (remain.isEmpty()) {
                regHome.remove(impu);
            }
            return;
        }
        // Lab: one live Contact per IMPU (Linphone / dashboard A-B). Drop stale devices.
        List<ImsBinding> existing = ImsBinding.list("impu", impu);
        ImsBinding b = null;
        for (ImsBinding row : existing) {
            if (contact.equals(row.contact)) {
                b = row;
            } else {
                row.delete();
            }
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
        if (impu == null || impu.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        for (String key : ImsIdentityKeys.bindingKeys(impu)) {
            List<ImsBinding> all = ImsBinding.list("impu", key);
            Optional<ImsBinding> hit = all.stream()
                    .filter(b -> b.expiresAt != null && b.expiresAt.isAfter(now))
                    .max(java.util.Comparator.comparing(b -> b.expiresAt));
            if (hit.isPresent()) {
                return hit;
            }
        }
        return Optional.empty();
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
