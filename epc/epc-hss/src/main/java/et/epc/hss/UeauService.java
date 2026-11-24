package et.epc.hss;

import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.crypto.Auts;
import et.epc.crypto.Bytes;
import et.epc.crypto.FiveGAka;
import et.epc.crypto.UmtsVector;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * UDM/ARPF face of the HSS for 5G: Nudm_UEAuthentication <em>Get</em> (generate-auth-data,
 * TS 29.503 §5.4.2.2 / §6.3.3.2.4) over the same Postgres subscriber/secret SoT and the
 * <strong>same SQN space</strong> as S6a AIR, Cx MAR and MAP SendAuthenticationInfo
 * ({@link HlrAuthService}).
 *
 * <p>Transport-free: {@code UdmUeauSbb} (ra-http-server) maps the JSON body onto
 * {@link AuthenticationInfoRequest} and serialises the {@link Outcome}. All octet strings on
 * the wire are hex, so the records here carry hex strings exactly as TS 29.503 §6.3.6 names them.
 *
 * <h2>Procedure</h2>
 * <ol>
 *   <li>Request IEs (TS 29.503 Table 6.3.6.2.2-1): {@code servingNetworkName} M,
 *       {@code ausfInstanceId} M (uuid), {@code resynchronizationInfo} O — syntax only, 400 on error.</li>
 *   <li>Identity: {@code imsi-<digits>} SUPI, or a SUCI with the <em>null</em> protection scheme
 *       ({@code suci-0-<mcc>-<mnc>-<routingInd>-0-0-<msin>}, TS 23.003 §2.2B) → IMSI = MCC‖MNC‖MSIN.
 *       Profile A/B or operator schemes → 501 UNSUPPORTED_PROTECTION_SCHEME; this UDM has no SIDF,
 *       it never guesses a SUPI.</li>
 *   <li>Subscriber lookup ({@link HssRepository#find}); unknown or disabled → 404 USER_NOT_FOUND.</li>
 *   <li>Serving network authorisation (rule below) → 403 on refusal.</li>
 *   <li>Ki/OPc/AMF from {@link SecretStore}; missing → 500 SYSTEM_FAILURE.</li>
 *   <li>Re-synchronisation (TS 33.501 §6.1.3.3.2 → TS 33.102 §6.3.5): AUTS MAC-S verified and SQN_MS
 *       adopted with {@link HssRepository#forceSqn}; bad MAC-S → 403 AUTHENTICATION_REJECTED.</li>
 *   <li>One fresh SQN ({@link HssRepository#reserveSqn}) → MILENAGE AV → XRES* (TS 33.501 A.4) and
 *       K<sub>AUSF</sub> (A.2) bound to the serving network name → 200 {@code 5G_AKA / 5G_HE_AKA}.</li>
 * </ol>
 *
 * <h2>Serving network authorisation rule</h2>
 * The PLMN encoded in the serving network name is allowed when it is the subscriber's home PLMN
 * ({@code SubscriberProfile.homePlmn}) or one of the configured {@code allowedServingPlmns}
 * (host MNO / roaming partners). PLMNs are compared on the SN-name form, so a 2-digit MNC equals its
 * {@code 0}-padded 3-digit spelling (TS 24.501 §9.12.1 makes them indistinguishable anyway). The SNPN
 * form ({@code …3gppnetwork.org:<NID>}) is well-formed per TS 29.571 but not served here → 403.
 *
 * <p>Secrets: Ki, OPc, CK, IK and K<sub>AUSF</sub> are never logged; {@link Av5GHeAka#toString()}
 * redacts XRES* and K<sub>AUSF</sub> so an accidental {@code log(result)} cannot leak them.
 */
public final class UeauService {

    private static final Logger LOG = LogManager.getLogger(UeauService.class);

    /** TS 29.503 Table 6.3.6.3.3-1 / 6.3.6.3.4-1. */
    public static final String AUTH_TYPE_5G_AKA = "5G_AKA";
    public static final String AV_TYPE_5G_HE_AKA = "5G_HE_AKA";

    /** Application errors: TS 29.503 Table 6.3.7.3-1 and the common TS 29.500 Table 5.2.7.2-1. */
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String AUTHENTICATION_REJECTED = "AUTHENTICATION_REJECTED";
    public static final String UNSUPPORTED_PROTECTION_SCHEME = "UNSUPPORTED_PROTECTION_SCHEME";
    public static final String INVALID_HN_PUBLIC_KEY_IDENTIFIER = "INVALID_HN_PUBLIC_KEY_IDENTIFIER";
    public static final String INVALID_SCHEME_OUTPUT = "INVALID_SCHEME_OUTPUT";
    public static final String MANDATORY_IE_MISSING = "MANDATORY_IE_MISSING";
    public static final String MANDATORY_IE_INCORRECT = "MANDATORY_IE_INCORRECT";
    public static final String OPTIONAL_IE_INCORRECT = "OPTIONAL_IE_INCORRECT";
    public static final String SYSTEM_FAILURE = "SYSTEM_FAILURE";

    /**
     * Cause for a serving network this UDM refuses. TS 29.503 removed {@code SERVING_NETWORK_NOT_AUTHORIZED}
     * from Nudm_UEAU (CR 0450, v16.4.0; the SN check belongs to the AUSF, TS 29.509), so the UDM answers
     * 403 with the Nudm_UEAU cause {@link #AUTHENTICATION_REJECTED}.
     */
    public static final String SN_NOT_AUTHORIZED_CAUSE = AUTHENTICATION_REJECTED;

    /** TS 29.571 ServingNetworkName pattern (PLMN form, optional SNPN NID). */
    private static final Pattern SN_NAME_WIRE =
            Pattern.compile("5G:mnc([0-9]{3})\\.mcc([0-9]{3})\\.3gppnetwork\\.org(:[A-F0-9]{11})?");
    private static final Pattern SUPI_IMSI = Pattern.compile("imsi-([0-9]{5,15})");
    /** TS 23.003 §2.2B: suci-<type>-<mcc>-<mnc>-<routingInd>-<scheme>-<hnKeyId>-<schemeOutput>. */
    private static final Pattern SUCI = Pattern.compile(
            "suci-([0-7])-([0-9]{3})-([0-9]{2,3})-([0-9]{1,4})-([0-9A-Fa-f])-([0-9]{1,3})-(.+)");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX_RAND = Pattern.compile("[0-9A-Fa-f]{32}");
    private static final Pattern HEX_AUTS = Pattern.compile("[0-9A-Fa-f]{28}");
    private static final HexFormat HEX = HexFormat.of();

    // ---------------------------------------------------------------- wire records (TS 29.503 §6.3.6)

    /** TS 29.503 Table 6.3.6.2.6-1 — hex RAND (16 octets) and AUTS (14 octets). */
    public record ResynchronizationInfo(String rand, String auts) {}

    /** TS 29.503 Table 6.3.6.2.2-1 (subset served here). {@code resynchronizationInfo} may be null. */
    public record AuthenticationInfoRequest(String servingNetworkName,
                                            ResynchronizationInfo resynchronizationInfo,
                                            String ausfInstanceId) {}

    /** Result of generate-auth-data: either a 200 body or a ProblemDetails. */
    public sealed interface Outcome permits AuthenticationInfoResult, ProblemDetails {}

    /** TS 29.503 Table 6.3.6.2.5-1 (Av5GHeAka); all fields lower-case hex. */
    public record Av5GHeAka(String avType, String rand, String xresStar, String autn, String kausf) {
        @Override
        public String toString() {
            return "Av5GHeAka[avType=" + avType + ", rand=" + rand + ", autn=" + autn
                    + ", xresStar=<redacted>, kausf=<redacted>]";
        }
    }

    /**
     * TS 29.503 Table 6.3.6.2.3-1. {@code supi} is always filled (the spec makes it mandatory when the
     * URI carried a SUCI and allows it otherwise).
     */
    public record AuthenticationInfoResult(String authType, Av5GHeAka authenticationVector, String supi)
            implements Outcome {}

    /** TS 29.500 §5.2.7 ProblemDetails (subset): HTTP status, application error cause, free text detail. */
    public record ProblemDetails(int status, String cause, String detail) implements Outcome {}

    // ---------------------------------------------------------------- service

    private final HssRepository repo;
    private final SecretStore secrets;
    /** Allowed serving networks beyond the subscriber's home PLMN, as canonical SN names. */
    private final Set<String> allowedServingNetworks;

    /** Home PLMN only. */
    public UeauService(HssRepository repo, SecretStore secrets) {
        this(repo, secrets, Set.of());
    }

    /**
     * @param allowedServingPlmns PLMNs (besides each subscriber's home PLMN) whose serving network
     *                            name is accepted, e.g. the host MNO; empty = home PLMN only
     */
    public UeauService(HssRepository repo, SecretStore secrets, Collection<PlmnId> allowedServingPlmns) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.allowedServingNetworks = Objects.requireNonNull(allowedServingPlmns, "allowedServingPlmns")
                .stream().map(FiveGAka::servingNetworkName).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * POST {apiRoot}/nudm-ueau/v1/{supiOrSuci}/security-information/generate-auth-data.
     *
     * @param supiOrSuci the {supiOrSuci} URI variable
     * @param request    the AuthenticationInfoRequest body
     */
    public Outcome generateAuthData(String supiOrSuci, AuthenticationInfoRequest request) {
        if (request == null) {
            return problem(400, MANDATORY_IE_MISSING, "AuthenticationInfoRequest body missing");
        }
        // 1. request syntax — before any lookup or SQN reservation
        String sn = request.servingNetworkName();
        if (sn == null || sn.isEmpty()) {
            return problem(400, MANDATORY_IE_MISSING, "servingNetworkName missing");
        }
        if (!SN_NAME_WIRE.matcher(sn).matches()) {
            return problem(400, MANDATORY_IE_INCORRECT, "servingNetworkName malformed");
        }
        String ausf = request.ausfInstanceId();
        if (ausf == null || ausf.isEmpty()) {
            return problem(400, MANDATORY_IE_MISSING, "ausfInstanceId missing");
        }
        if (!UUID.matcher(ausf).matches()) {
            return problem(400, MANDATORY_IE_INCORRECT, "ausfInstanceId is not an NfInstanceId (uuid)");
        }
        ResynchronizationInfo resync = request.resynchronizationInfo();
        byte[] resyncRand = null;
        byte[] resyncAuts = null;
        if (resync != null) {
            if (resync.rand() == null || !HEX_RAND.matcher(resync.rand()).matches()
                    || resync.auts() == null || !HEX_AUTS.matcher(resync.auts()).matches()) {
                return problem(400, OPTIONAL_IE_INCORRECT,
                        "resynchronizationInfo needs rand (16 octets) and auts (14 octets) in hex");
            }
            resyncRand = HEX.parseHex(resync.rand());
            resyncAuts = HEX.parseHex(resync.auts());
        }

        // 2. identity
        Imsi imsi;
        switch (resolve(supiOrSuci)) {
            case IdentityError(ProblemDetails p) -> {
                return p;
            }
            case Resolved(Imsi i) -> imsi = i;
        }

        // 3. subscriber
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        if (rec == null || !rec.profile().enabled()) {
            return problem(404, USER_NOT_FOUND, "user does not exist in the HPLMN");
        }

        // 4. serving network authorisation
        if (!servingNetworkAllowed(sn, rec.profile().homePlmn())) {
            return problem(403, SN_NOT_AUTHORIZED_CAUSE, "serving network not authorized");
        }

        // 5. secrets
        byte[] ki;
        byte[] opc;
        byte[] amf;
        try {
            ki = secrets.ki(imsi);
            opc = secrets.opc(imsi);
            amf = secrets.amf(imsi);
        } catch (RuntimeException missingSecret) {
            LOG.warn("Nudm-UEAU: subscriber key material unavailable ({})", missingSecret.getClass().getSimpleName());
            return problem(500, SYSTEM_FAILURE, "authentication data unavailable");
        }

        // 6. re-synchronisation
        if (resyncRand != null) {
            long sqnMs;
            try {
                sqnMs = Auts.recoverSqnMs(ki, opc, resyncRand, resyncAuts);
            } catch (IllegalArgumentException badMacS) {
                return problem(403, AUTHENTICATION_REJECTED, "AUTS verification failed");
            }
            repo.forceSqn(imsi, sqnMs);
        }

        // 7. one fresh SQN → 5G HE AV
        UmtsVector av = UmtsVector.generate(ki, opc, amf, repo.reserveSqn(imsi));
        Av5GHeAka vector = new Av5GHeAka(
                AV_TYPE_5G_HE_AKA,
                Bytes.toHex(av.rand()),
                Bytes.toHex(FiveGAka.xresStar(av, sn)),
                Bytes.toHex(av.autn()),
                Bytes.toHex(FiveGAka.kausf(av, sn)));
        return new AuthenticationInfoResult(AUTH_TYPE_5G_AKA, vector, "imsi-" + imsi.digits());
    }

    private boolean servingNetworkAllowed(String sn, PlmnId home) {
        if (!FiveGAka.isValidServingNetworkName(sn)) {
            return false; // SNPN form (":<NID>") — well-formed, not served by this UDM
        }
        return sn.equals(FiveGAka.servingNetworkName(home)) || allowedServingNetworks.contains(sn);
    }

    // ---------------------------------------------------------------- SUPI / SUCI

    private sealed interface Identity permits Resolved, IdentityError {}

    private record Resolved(Imsi imsi) implements Identity {}

    private record IdentityError(ProblemDetails problem) implements Identity {}

    /** {@code imsi-…} SUPI or null-scheme IMSI SUCI → IMSI; anything else → ProblemDetails. */
    private static Identity resolve(String supiOrSuci) {
        if (supiOrSuci == null || supiOrSuci.isEmpty()) {
            return notFound("supiOrSuci missing");
        }
        Matcher supi = SUPI_IMSI.matcher(supiOrSuci);
        if (supi.matches()) {
            return toImsi(supi.group(1), notFound("SUPI is not a valid IMSI"));
        }
        if (!supiOrSuci.startsWith("suci-")) {
            return notFound("only IMSI-based SUPI is served");
        }
        Matcher suci = SUCI.matcher(supiOrSuci);
        if (!suci.matches()) {
            return notFound("SUCI malformed");
        }
        if (!"0".equals(suci.group(5))) {
            return error(501, UNSUPPORTED_PROTECTION_SCHEME, "only the null protection scheme is supported");
        }
        if (!"0".equals(suci.group(1))) {
            return notFound("only IMSI-type SUCI is served");
        }
        if (!"0".equals(suci.group(6))) {
            return error(403, INVALID_HN_PUBLIC_KEY_IDENTIFIER,
                    "null scheme requires home network public key identifier 0");
        }
        String msin = suci.group(7);
        IdentityError badOutput = error(403, INVALID_SCHEME_OUTPUT, "null-scheme MSIN invalid");
        if (!msin.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return badOutput;
        }
        return toImsi(suci.group(2) + suci.group(3) + msin, badOutput);
    }

    private static Identity toImsi(String digits, IdentityError onError) {
        try {
            return new Resolved(new Imsi(digits));
        } catch (IllegalArgumentException e) {
            return onError;
        }
    }

    private static IdentityError notFound(String detail) {
        return error(404, USER_NOT_FOUND, detail);
    }

    private static IdentityError error(int status, String cause, String detail) {
        return new IdentityError(new ProblemDetails(status, cause, detail));
    }

    private static ProblemDetails problem(int status, String cause, String detail) {
        return new ProblemDetails(status, cause, detail);
    }
}
