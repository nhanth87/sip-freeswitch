package et.epc.hss;

import et.epc.api.Imsi;
import et.epc.crypto.Auts;
import et.epc.crypto.UmtsVector;
import java.util.ArrayList;
import java.util.List;

/**
 * HLR/AuC face of the HSS for 2G/3G serving nodes: MAP SendAuthenticationInfo
 * (TS 29.002 §8.5.2) over the same Postgres subscriber/secret SoT as S6a/Cx.
 *
 * <ul>
 *   <li>one fresh TS 33.102 Annex C SQN per vector ({@link HssRepository#reserveSqn});</li>
 *   <li>re-synchronisation (TS 33.102 §6.3.5): AUTS is verified (MAC-S) and SQN_MS adopted
 *       before the new vectors are built;</li>
 *   <li>MAP v3 (UMTS) → quintuplets; MAP v2 (GSM serving node) → triplets derived with
 *       c2/c3 (§6.8.1.2) from a USIM subscriber's quintuplets.</li>
 * </ul>
 * Transport-free: the SBB maps the answer onto ra-jss7 commands.
 */
public final class HlrAuthService {

    /** TS 29.002 §17.6.1 error codes used by SendAuthenticationInfo. */
    public static final long ERR_UNKNOWN_SUBSCRIBER = 1;
    public static final long ERR_SYSTEM_FAILURE = 34;
    public static final long ERR_UNEXPECTED_DATA_VALUE = 36;

    /** TS 29.002: numberOfRequestedVectors 1..5. */
    public static final int MAX_VECTORS = 5;

    /** Either vectors (quintuplets, or triplets when {@code gsm}) or a MAP error. */
    public record Answer(List<UmtsVector> quintuplets, List<UmtsVector.GsmTriplet> triplets, Long mapError) {
        static Answer error(long code) {
            return new Answer(List.of(), List.of(), code);
        }
    }

    private final HssRepository repo;
    private final SecretStore secrets;

    public HlrAuthService(HssRepository repo, SecretStore secrets) {
        this.repo = repo;
        this.secrets = secrets;
    }

    /**
     * @param requested   numberOfRequestedVectors (clamped to 1..5)
     * @param mapVersion  MAP application-context version of the dialog (2 → triplets)
     * @param resyncRand  RAND of re-synchronisationInfo, or {@code null}
     * @param resyncAuts  AUTS of re-synchronisationInfo, or {@code null}
     */
    public Answer sendAuthenticationInfo(Imsi imsi, int requested, int mapVersion,
                                         byte[] resyncRand, byte[] resyncAuts) {
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        if (rec == null || !rec.profile().enabled()) {
            return Answer.error(ERR_UNKNOWN_SUBSCRIBER);
        }
        byte[] ki;
        byte[] opc;
        byte[] amf;
        try {
            ki = secrets.ki(imsi);
            opc = secrets.opc(imsi);
            amf = secrets.amf(imsi);
        } catch (RuntimeException missingSecret) {
            return Answer.error(ERR_SYSTEM_FAILURE);
        }
        if (resyncRand != null || resyncAuts != null) {
            if (resyncRand == null || resyncAuts == null || resyncRand.length != 16) {
                return Answer.error(ERR_UNEXPECTED_DATA_VALUE);
            }
            try {
                repo.forceSqn(imsi, Auts.recoverSqnMs(ki, opc, resyncRand, resyncAuts));
            } catch (IllegalArgumentException badMacS) {
                return Answer.error(ERR_UNEXPECTED_DATA_VALUE);
            }
        }
        int n = Math.max(1, Math.min(MAX_VECTORS, requested));
        List<UmtsVector> vectors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            vectors.add(UmtsVector.generate(ki, opc, amf, repo.reserveSqn(imsi)));
        }
        if (mapVersion < 3) {
            return new Answer(List.of(), vectors.stream().map(UmtsVector::toGsmTriplet).toList(), null);
        }
        return new Answer(List.copyOf(vectors), List.of(), null);
    }
}
