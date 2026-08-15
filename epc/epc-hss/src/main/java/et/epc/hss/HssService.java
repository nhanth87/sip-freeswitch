package et.epc.hss;

import et.epc.api.EpsAuthVector;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.crypto.Bytes;
import et.epc.crypto.EpsAka;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Standalone HSS/AuC — S6a procedures. SQN is reserved atomically on the subscriber
 * row before AIA is returned (not a heap-only pending vector).
 */
public final class HssService {

    private final HssRepository repo;
    private final SecretStore secrets;
    private final PlmnId homePlmn;

    public HssService(HssRepository repo, SecretStore secrets, PlmnId homePlmn) {
        this.repo = Objects.requireNonNull(repo);
        this.secrets = Objects.requireNonNull(secrets);
        this.homePlmn = Objects.requireNonNull(homePlmn);
    }

    public S6aMessage handle(S6aMessage req) {
        return switch (req.command()) {
            case AIR -> air(req);
            case ULR -> ulr(req);
            case CLR -> clr(req);
            case PUR -> pur(req);
            case IDR -> ok(S6aCommand.IDA, imsiOf(req));
            case DSR -> dsr(req);
            case RSR -> ok(S6aCommand.RSA, imsiOf(req));
            case NOR -> ok(S6aCommand.NOA, imsiOf(req));
            default -> error(req.command(), S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE);
        };
    }

    private S6aMessage air(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        if (rec == null || !rec.profile().enabled()) {
            return error(S6aCommand.AIA, S6aAvp.ERROR_USER_UNKNOWN);
        }
        byte[] auts = req.avp(S6aAvp.RE_SYNCHRONIZATION_INFO);
        if (auts != null && auts.length >= 14) {
            byte[] rand = req.avp(S6aAvp.RAND);
            long recovered = EpsAka.sqnFromAuts(
                    secrets.ki(imsi), secrets.opc(imsi), rand, Bytes.copy(auts, 0, 14));
            repo.forceSqn(imsi, recovered);
        }
        long used = repo.reserveSqn(imsi);
        EpsAuthVector v = EpsAka.generate(
                secrets.ki(imsi), secrets.opc(imsi), secrets.amf(imsi), used, visitedPlmn(req));
        return S6aMessage.builder(S6aCommand.AIA, false)
                .putUtf8(S6aAvp.USER_NAME, imsi.digits())
                .put(S6aAvp.RESULT_CODE, u32(S6aAvp.SUCCESS))
                .put(S6aAvp.RAND, v.rand())
                .put(S6aAvp.XRES, v.xres())
                .put(S6aAvp.AUTN, v.autn())
                .put(S6aAvp.KASME, v.kasme())
                .build();
    }

    private S6aMessage ulr(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        if (rec == null || !rec.profile().enabled()) {
            return error(S6aCommand.ULA, S6aAvp.ERROR_USER_UNKNOWN);
        }
        String old = rec.servingMmeHost();
        String neu = req.avpUtf8(S6aAvp.ORIGIN_HOST);
        repo.setServingMme(imsi, neu);
        repo.setPurged(imsi, false);
        S6aMessage.Builder b = S6aMessage.builder(S6aCommand.ULA, false)
                .putUtf8(S6aAvp.USER_NAME, imsi.digits())
                .put(S6aAvp.RESULT_CODE, u32(S6aAvp.SUCCESS))
                .putUtf8(S6aAvp.SUBSCRIPTION_DATA, rec.profile().defaultApn());
        if (old != null && neu != null && !old.equals(neu)) {
            b.putUtf8(S6aAvp.CANCELLATION_TYPE, "MME_UPDATE_PROCEDURE:" + old);
        }
        return b.build();
    }

    private S6aMessage clr(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        repo.setServingMme(imsi, null);
        return ok(S6aCommand.CLA, imsi);
    }

    private S6aMessage pur(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        repo.setPurged(imsi, true);
        return ok(S6aCommand.PUA, imsi);
    }

    private S6aMessage dsr(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        repo.setServingMme(imsi, null);
        return ok(S6aCommand.DSA, imsi);
    }

    private S6aMessage ok(S6aCommand cmd, Imsi imsi) {
        return S6aMessage.builder(cmd, false)
                .putUtf8(S6aAvp.USER_NAME, imsi.digits())
                .put(S6aAvp.RESULT_CODE, u32(S6aAvp.SUCCESS))
                .build();
    }

    private S6aMessage error(S6aCommand cmd, long code) {
        S6aCommand ans = switch (cmd) {
            case AIR -> S6aCommand.AIA;
            case ULR -> S6aCommand.ULA;
            default -> cmd;
        };
        return S6aMessage.builder(ans, false).put(S6aAvp.RESULT_CODE, u32(code)).build();
    }

    private Imsi imsiOf(S6aMessage req) {
        String s = req.avpUtf8(S6aAvp.USER_NAME);
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("missing User-Name");
        }
        return new Imsi(s);
    }

    private PlmnId visitedPlmn(S6aMessage req) {
        byte[] v = req.avp(S6aAvp.VISITED_PLMN_ID);
        if (v == null || v.length != 3) {
            return homePlmn;
        }
        int mcc1 = v[0] & 0x0f;
        int mcc2 = (v[0] >> 4) & 0x0f;
        int mcc3 = v[1] & 0x0f;
        int mnc3 = (v[1] >> 4) & 0x0f;
        int mnc1 = v[2] & 0x0f;
        int mnc2 = (v[2] >> 4) & 0x0f;
        String mcc = "" + mcc1 + mcc2 + mcc3;
        String mnc = mnc3 == 0xF ? "" + mnc1 + mnc2 : "" + mnc1 + mnc2 + mnc3;
        return new PlmnId(mcc, mnc);
    }

    private static byte[] u32(long v) {
        return ByteBuffer.allocate(4).putInt((int) v).array();
    }

    public PlmnId homePlmn() {
        return homePlmn;
    }
}
