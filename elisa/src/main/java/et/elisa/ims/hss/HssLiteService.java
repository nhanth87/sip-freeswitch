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

    @ConfigProperty(name = "sf.cluster.node-id", defaultValue = "elisa-1")
    String nodeId;

    /** Pending AKA vectors keyed by callId / session. */
    private final ConcurrentHashMap<String, PendingAuth> pending = new ConcurrentHashMap<>();

    public record PendingAuth(String impi, String impu, byte[] xres, long nextSqn) {}

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
        return findByImpi(noSip).or(() -> findByImpi(user));
    }

    @Transactional
    public Map<Integer, String> handleMar(String impi, String impu, String callId) {
        ImsSubscriber sub = findByImpi(impi).orElse(null);
        if (sub == null || !sub.enabled) {
            return Map.of(CxCodec.RESULT_CODE, "5001");
        }
        byte[] ki = AkaMd5.parseHex(sub.ki);
        byte[] opc = AkaMd5.parseHex(sub.opc);
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
        if (findByImpi(impi).isEmpty() && findByImpu(impu).isEmpty()) {
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
