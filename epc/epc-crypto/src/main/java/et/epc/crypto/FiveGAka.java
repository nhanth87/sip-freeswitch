package et.epc.crypto;

import et.epc.api.PlmnId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 5G AKA key derivation for the home network (UDM/ARPF and AUSF side), TS 33.501 Annex A.
 *
 * <p>Pure functions: no transport, no state, no logging. Every result is a fresh array and inputs are
 * never modified. The AV inputs (RAND, CK, IK, RES, SQN⊕AK) come from MILENAGE through
 * {@link UmtsVector} — 5G AKA reuses the UMTS AV and only adds the derivations below.
 *
 * <ul>
 *   <li>{@link #kdf} — generic KDF, TS 33.220 Annex B.2: {@code S = FC || P0 || L0 || … || Pn || Ln},
 *       {@code Li} = length of {@code Pi} in octets, 2 octets big-endian; output = HMAC-SHA-256(Key, S).</li>
 *   <li>{@link #kausf} — K<sub>AUSF</sub>, TS 33.501 A.2: FC = 0x6A, P0 = serving network name,
 *       P1 = SQN⊕AK; Key = CK||IK; 256 bits.</li>
 *   <li>{@link #xresStar} — XRES* (= RES* on the UE side), TS 33.501 A.4: FC = 0x6B, P0 = serving
 *       network name, P1 = RAND, P2 = RES; Key = CK||IK; the 128 least significant bits of the output,
 *       i.e. the <em>last</em> 16 octets.</li>
 *   <li>{@link #hxresStar} — HXRES* (= HRES*), TS 33.501 A.5: the 128 least significant bits of
 *       SHA-256(RAND || XRES*).</li>
 * </ul>
 *
 * <p>Serving network name (TS 33.501 §6.1.1.4, TS 24.501 §9.12.1): {@code 5G:mnc<MNC>.mcc<MCC>.3gppnetwork.org}
 * with a 3-digit MNC (a 2-digit MNC is left-padded with {@code 0}) and a 3-digit MCC. It is encoded as
 * UTF-8 octets in the KDF. A mismatch with the name the UE uses does not fail here — it makes the UE
 * reject the network (MAC/RES* mismatch) — so derivations accept only the PLMN form and fail fast.
 * The SNPN form with a {@code :<NID>} suffix is not supported (this MVNO has no SNPN).
 */
public final class FiveGAka {

    /** TS 33.501 A.2 — K<sub>AUSF</sub> derivation. */
    public static final byte FC_KAUSF = 0x6A;
    /** TS 33.501 A.4 — RES* / XRES* derivation. */
    public static final byte FC_XRES_STAR = 0x6B;

    /** Octet lengths fixed by TS 33.102 / TS 33.501. */
    public static final int CK_LEN = 16;
    public static final int IK_LEN = 16;
    public static final int RAND_LEN = 16;
    public static final int SQN_XOR_AK_LEN = 6;
    public static final int KAUSF_LEN = 32;
    public static final int XRES_STAR_LEN = 16;
    public static final int HXRES_STAR_LEN = 16;
    /** RES is 32..128 bits (TS 33.102 §6.3.2); MILENAGE f2 gives 64. */
    public static final int RES_MIN_LEN = 4;
    public static final int RES_MAX_LEN = 16;

    private static final int MAX_PARAM_LEN = 0xFFFF;

    private static final Pattern SN_NAME =
            Pattern.compile("5G:mnc[0-9]{3}\\.mcc[0-9]{3}\\.3gppnetwork\\.org");

    private FiveGAka() {}

    // ---------------------------------------------------------------- generic KDF (TS 33.220 B.2)

    /** TS 33.220 Annex B.2 KDF: HMAC-SHA-256(key, FC || P0 || L0 || … || Pn || Ln). 32 octets. */
    public static byte[] kdf(byte[] key, byte fc, byte[]... params) {
        Objects.requireNonNull(key, "key");
        return Kasme.hmacSha256(key, kdfInput(fc, params));
    }

    /** The KDF input string S (TS 33.220 B.2.1). Package-private so tests can assert the layout. */
    static byte[] kdfInput(byte fc, byte[]... params) {
        Objects.requireNonNull(params, "params");
        int n = 1;
        for (int i = 0; i < params.length; i++) {
            byte[] p = Objects.requireNonNull(params[i], "P" + i);
            if (p.length > MAX_PARAM_LEN) {
                throw new IllegalArgumentException("P" + i + " longer than 65535 octets: " + p.length);
            }
            n += p.length + 2;
        }
        byte[] s = new byte[n];
        s[0] = fc;
        int o = 1;
        for (byte[] p : params) {
            System.arraycopy(p, 0, s, o, p.length);
            o += p.length;
            s[o++] = (byte) (p.length >>> 8);
            s[o++] = (byte) p.length;
        }
        return s;
    }

    // ---------------------------------------------------------------- TS 33.501 Annex A

    /** K<sub>AUSF</sub> = KDF(CK||IK, 0x6A, SN name, SQN⊕AK) — TS 33.501 A.2. 32 octets. */
    public static byte[] kausf(byte[] ck, byte[] ik, String servingNetworkName, byte[] sqnXorAk) {
        requireLen(ck, CK_LEN, "CK");
        requireLen(ik, IK_LEN, "IK");
        requireLen(sqnXorAk, SQN_XOR_AK_LEN, "SQN⊕AK");
        byte[] sn = snNameOctets(servingNetworkName);
        return kdf(Bytes.concat(ck, ik), FC_KAUSF, sn, sqnXorAk);
    }

    /** K<sub>AUSF</sub> from a UMTS AV: SQN⊕AK is the first 6 octets of AUTN (TS 33.102 §6.3.2). */
    public static byte[] kausf(UmtsVector av, String servingNetworkName) {
        Objects.requireNonNull(av, "av");
        return kausf(av.ck(), av.ik(), servingNetworkName, sqnXorAk(av.autn()));
    }

    /** XRES* = lowest 128 bits of KDF(CK||IK, 0x6B, SN name, RAND, RES) — TS 33.501 A.4. 16 octets. */
    public static byte[] xresStar(byte[] ck, byte[] ik, String servingNetworkName, byte[] rand, byte[] res) {
        requireLen(ck, CK_LEN, "CK");
        requireLen(ik, IK_LEN, "IK");
        requireLen(rand, RAND_LEN, "RAND");
        Objects.requireNonNull(res, "RES");
        if (res.length < RES_MIN_LEN || res.length > RES_MAX_LEN) {
            throw new IllegalArgumentException("RES must be 4..16 octets, got " + res.length);
        }
        byte[] sn = snNameOctets(servingNetworkName);
        byte[] out = kdf(Bytes.concat(ck, ik), FC_XRES_STAR, sn, rand, res);
        return lowest128(out);
    }

    /** XRES* from a UMTS AV (RES = XRES of the AV). */
    public static byte[] xresStar(UmtsVector av, String servingNetworkName) {
        Objects.requireNonNull(av, "av");
        return xresStar(av.ck(), av.ik(), servingNetworkName, av.rand(), av.xres());
    }

    /** HXRES* = lowest 128 bits of SHA-256(RAND || XRES*) — TS 33.501 A.5. 16 octets. */
    public static byte[] hxresStar(byte[] rand, byte[] xresStar) {
        requireLen(rand, RAND_LEN, "RAND");
        requireLen(xresStar, XRES_STAR_LEN, "XRES*");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(rand);
            sha.update(xresStar);
            return lowest128(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** SQN⊕AK = the first 6 octets of AUTN (AUTN = SQN⊕AK || AMF || MAC-A). */
    public static byte[] sqnXorAk(byte[] autn) {
        requireLen(autn, 16, "AUTN");
        return Bytes.copy(autn, 0, SQN_XOR_AK_LEN);
    }

    // ---------------------------------------------------------------- serving network name

    /** True when {@code name} is {@code 5G:mnc<3 digits>.mcc<3 digits>.3gppnetwork.org} (TS 24.501 §9.12.1). */
    public static boolean isValidServingNetworkName(String name) {
        return name != null && SN_NAME.matcher(name).matches();
    }

    /** Returns {@code name} unchanged, or throws {@link IllegalArgumentException} when it is not a valid PLMN SN name. */
    public static String requireServingNetworkName(String name) {
        if (!isValidServingNetworkName(name)) {
            throw new IllegalArgumentException(
                    "serving network name must be 5G:mnc<3d>.mcc<3d>.3gppnetwork.org, got " + name);
        }
        return name;
    }

    /** Builds the SN name for a PLMN; a 2-digit MNC is left-padded with {@code 0} (TS 24.501 §9.12.1). */
    public static String servingNetworkName(PlmnId plmn) {
        Objects.requireNonNull(plmn, "plmn");
        String mnc = plmn.mnc().length() == 2 ? "0" + plmn.mnc() : plmn.mnc();
        return requireServingNetworkName("5G:mnc" + mnc + ".mcc" + plmn.mcc() + ".3gppnetwork.org");
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] snNameOctets(String servingNetworkName) {
        return requireServingNetworkName(servingNetworkName).getBytes(StandardCharsets.UTF_8);
    }

    /** The 128 least significant bits of a 256-bit string = its last 16 octets. */
    private static byte[] lowest128(byte[] out256) {
        return Bytes.copy(out256, out256.length - 16, 16);
    }

    private static void requireLen(byte[] b, int len, String name) {
        Objects.requireNonNull(b, name);
        if (b.length != len) {
            throw new IllegalArgumentException(name + " must be " + len + " octets, got " + b.length);
        }
    }
}
