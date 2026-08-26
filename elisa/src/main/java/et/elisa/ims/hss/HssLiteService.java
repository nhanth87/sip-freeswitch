package et.elisa.ims.hss;

import et.elisa.ims.aka.AkaMd5;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.CxCodec;
import et.elisa.persist.ImsBinding;
import et.elisa.persist.ImsSubscriber;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HssLiteService {

    @Inject ImsRoleFlags roles;
    @Inject et.elisa.security.ImsSecretStore secrets;

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String nodeId;

    /** Pending AKA vectors keyed by callId / session. */
    private final ConcurrentHashMap<String, PendingAuth> pending = new ConcurrentHashMap<>();
    /** Pending SIP Digest (dashboard Linphone/Zoiper) keyed by callId / IMPI. */
    private final ConcurrentHashMap<String, PendingDigest> digestPending = new ConcurrentHashMap<>();

    public record PendingAuth(String impi, String impu, byte[] xres, long nextSqn) {}

    public record PendingDigest(String impi, String impu, String nonce, String ha1) {}

    @Transactional
    public Optional<ImsSubscriber> findByImpi(String impi) {
        return Optional.ofNullable(ImsSubscriber.find("impi", impi).firstResult());
    }

    @Transactional
    public Optional<ImsSubscriber> findByImpu(String impu) {
        return Optional.ofNullable(ImsSubscriber.find("impu", impu).firstResult());
    }

    @Transactional
    public Optional<ImsSubscriber> findByMsisdn(String msisdn) {
        if (msisdn == null || msisdn.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ImsSubscriber.find("msisdn", msisdn.trim()).firstResult());
    }

    /** Resolve profile from SIP URI, bare user, or MSISDN. */
    @Transactional
    public Optional<ImsSubscriber> resolveIdentity(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String s = raw.trim();
        Optional<ImsSubscriber> byMsisdn = findByMsisdn(s);
        if (byMsisdn.isPresent()) {
            return byMsisdn;
        }
        Optional<ImsSubscriber> byImpu = findByImpu(s);
        if (byImpu.isPresent()) {
            return byImpu;
        }
        String noSip = s.replaceFirst("(?i)^sip:", "");
        byImpu = findByImpu("sip:" + noSip);
        if (byImpu.isPresent()) {
            return byImpu;
        }
        int at = noSip.indexOf('@');
        String user = at > 0 ? noSip.substring(0, at) : noSip;
        byMsisdn = findByMsisdn(user);
        if (byMsisdn.isPresent()) {
            return byMsisdn;
        }
        Optional<ImsSubscriber> byImpi = findByImpi(noSip).or(() -> findByImpi(user));
        if (byImpi.isPresent()) {
            return byImpi;
        }
        // FS cid_num / xml_curl user-part: "alice" → sip:alice@<realm>
        String realm = ImsIdentityKeys.realmOrDefault(roles != null ? roles.realm() : null);
        String impu = ImsIdentityKeys.impu(user, realm);
        if (impu != null) {
            byImpu = findByImpu(impu);
            if (byImpu.isPresent()) {
                return byImpu;
            }
        }
        String impi = ImsIdentityKeys.impi(user, realm);
        return impi == null ? Optional.empty() : findByImpi(impi);
    }

    @Transactional
    public Map<Integer, String> handleMar(String impi, String impu, String callId) {
        ImsSubscriber sub = findByImpi(impi).orElse(null);
        if (sub == null || !sub.enabled) {
            return Map.of(CxCodec.RESULT_CODE, "5001");
        }
        // W2-a fail-closed: auth material only from AES-wrapped ims_secret.
        et.elisa.security.ImsSecretStore.StoredSecret material =
                secrets.find(impi).orElse(null);
        if (material == null) {
            return Map.of(CxCodec.RESULT_CODE, "5001");
        }
        byte[] ki = material.ki();
        byte[] opc = material.opc();
        byte[] amf = AkaMd5.parseHex(sub.amf == null ? "8000" : sub.amf);
        AkaMd5.Vector v = AkaMd5.generate(ki, opc, amf, sub.sqn);
        pending.put(callId == null ? impi : callId, new PendingAuth(impi, impu, v.xres(), v.nextSqn()));
        if (callId != null) {
            pending.put(impi, new PendingAuth(impi, impu, v.xres(), v.nextSqn()));
        }
        String randAutn = AkaMd5.toHex(v.rand()) + AkaMd5.toHex(v.autn());
        return CxCodec.maaAvps(impi, randAutn, AkaMd5.toHex(v.xres()),
                AkaMd5.toHex(v.ck()), AkaMd5.toHex(v.ik()));
    }

    public Optional<PendingAuth> takePending(String key) {
        Optional<PendingAuth> a = Optional.ofNullable(pending.remove(key));
        a.ifPresent(p -> {
            pending.remove(p.impi());
            if (key != null) {
                pending.remove(key);
            }
        });
        return a;
    }

    public Optional<PendingAuth> peekPending(String callId) {
        return Optional.ofNullable(pending.get(callId));
    }

    public void rememberDigest(String callId, String impi, String impu, String nonce, String ha1) {
        if (nonce == null || ha1 == null || ha1.isBlank() || impi == null || impi.isBlank()) {
            return;
        }
        PendingDigest d = new PendingDigest(impi, impu, nonce, ha1);
        if (callId != null && !callId.isBlank()) {
            digestPending.put(callId, d);
        }
        digestPending.put(impi, d);
    }

    public Optional<PendingDigest> peekDigest(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(digestPending.get(key));
    }

    public Optional<PendingDigest> takeDigest(String key) {
        Optional<PendingDigest> d = Optional.ofNullable(digestPending.remove(key));
        d.ifPresent(p -> {
            digestPending.remove(p.impi());
            if (key != null) {
                digestPending.remove(key);
            }
        });
        return d;
    }

    /**
     * Admin upsert by IMPI (then IMPU). Dashboard SIP password → {@code fsA1Hash}.
     * Ki/OPc (hex) go to the AES-wrapped {@code ims_secret} store, never the row.
     */
    @Transactional
    public ImsSubscriber upsert(ImsSubscriber incoming) {
        return upsert(incoming, null, null);
    }

    @Transactional
    public ImsSubscriber upsert(ImsSubscriber incoming, String kiHex, String opcHex) {
        if (incoming == null || incoming.impi == null || incoming.impi.isBlank()) {
            throw new IllegalArgumentException("impi required");
        }
        if (kiHex != null && !kiHex.isBlank() && opcHex != null && !opcHex.isBlank()) {
            secrets.putHex(incoming.impi.trim(), kiHex.trim(), opcHex.trim());
        }
        ImsSubscriber existing = findByImpi(incoming.impi.trim())
                .or(() -> incoming.impu == null ? Optional.empty() : findByImpu(incoming.impu.trim()))
                .orElse(null);
        Instant now = Instant.now();
        if (existing == null) {
            incoming.impi = incoming.impi.trim();
            if (incoming.impu != null) {
                incoming.impu = incoming.impu.trim();
            }
            incoming.enabled = true;
            incoming.createdAt = now;
            incoming.updatedAt = now;
            incoming.persist();
            return incoming;
        }
        if (incoming.impu != null && !incoming.impu.isBlank()) {
            existing.impu = incoming.impu.trim();
        }
        if (incoming.msisdn != null) {
            existing.msisdn = incoming.msisdn.isBlank() ? null : incoming.msisdn.trim();
        }
        if (incoming.displayName != null) {
            existing.displayName = incoming.displayName.isBlank() ? null : incoming.displayName.trim();
        }
        if (incoming.tenantId != null) {
            existing.tenantId = incoming.tenantId.isBlank() ? null : incoming.tenantId.trim();
        }
        if (incoming.amf != null && !incoming.amf.isBlank()) {
            existing.amf = incoming.amf.trim();
        }
        if (incoming.fsA1Hash != null && !incoming.fsA1Hash.isBlank()) {
            existing.fsA1Hash = incoming.fsA1Hash.trim();
        }
        if (incoming.ifcJson != null) {
            existing.ifcJson = incoming.ifcJson;
        }
        existing.enabled = incoming.enabled;
        existing.updatedAt = now;
        existing.persist();
        return existing;
    }

    /**
     * Cache XRES from Cx MAA (TS 29.229 SIP-Authorization) so S-CSCF can verify
     * the subsequent REGISTER when HSS is remote or when MAR short-circuits.
     */
    public void rememberChallenge(String callId, String impi, String impu, byte[] xres, long nextSqn) {
        if (xres == null || xres.length == 0 || impi == null || impi.isBlank()) {
            return;
        }
        PendingAuth p = new PendingAuth(impi, impu, xres, nextSqn);
        if (callId != null && !callId.isBlank()) {
            pending.put(callId, p);
        }
        pending.put(impi, p);
    }

    @Transactional
    public void commitSqn(String impi, long nextSqn) {
        ImsSubscriber sub = findByImpi(impi).orElse(null);
        if (sub == null) {
            return;
        }
        sub.sqn = nextSqn;
        sub.updatedAt = Instant.now();
        sub.persist();
    }

    @Transactional
    public Map<Integer, String> handleUar(String impi, String impu) {
        if (findByImpi(impi).isEmpty()
                && findByImpu(impu).isEmpty()
                && resolveIdentity(impi).isEmpty()
                && resolveIdentity(impu).isEmpty()) {
            return Map.of(CxCodec.RESULT_CODE, "5001");
        }
        return CxCodec.uaaAvps(roles.scscfUri());
    }

    @Transactional
    public Map<Integer, String> handleLir(String impu) {
        ImsBinding b = ImsBinding.find("impu", impu).firstResult();
        if (b != null && b.expiresAt.isAfter(Instant.now())) {
            return CxCodec.liaAvps(roles.scscfUri());
        }
        if (findByImpu(impu).isPresent() || resolveIdentity(impu).isPresent()) {
            return CxCodec.liaAvps(roles.scscfUri());
        }
        return Map.of(CxCodec.RESULT_CODE, "5001");
    }

    @Transactional
    public Map<Integer, String> handleSar(String impi, String impu) {
        if (findByImpi(impi).isEmpty()) {
            return Map.of(CxCodec.RESULT_CODE, "5001");
        }
        return CxCodec.saaOk();
    }

    public String nodeId() {
        return nodeId;
    }

    @Transactional
    public List<ImsSubscriber> listSubscribers() {
        return ImsSubscriber.listAll();
    }
}
