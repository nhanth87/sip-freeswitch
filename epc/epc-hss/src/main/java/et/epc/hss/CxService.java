package et.epc.hss;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.api.Imsi;
import et.epc.crypto.Bytes;
import et.epc.crypto.Milenage;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Production Cx face of the same subscriber repository. IMS S-CSCF registration
 * is stored separately from the EPS serving-MME field. SQN is reserved atomically
 * on the subscriber row, so a Cx MAR and an S6a AIR can never share a vector.
 *
 * <p>AKA vectors are generated with {@link Milenage} only — no dependency on the
 * legacy {@code elisa} AkaMd5 fallback path. The AKAv1-MD5 challenge nonce is
 * {@code RAND || AUTN}; XRES/CK/IK are returned as hex in the flat AVP map.</p>
 */
public final class CxService {

    /** Single-node lab default; a {@link ScscfPicker} replaces this by capabilities. */
    public static final String DEFAULT_SCSCF = "sip:scscf.ims.lab:5060";

    /** Lab charging-collection-function names echoed in SAR→SAA Charging-Information(618). */
    public static final String PRIMARY_CCF = "pcrf.ims.lab";
    public static final String SECONDARY_CCF = "pcrf-reserve.ims.lab";

    /** IMPU registration states persisted on {@code epc_subscriber.impu_reg_state} (TS 23.002). */
    public static final String STATE_NOT_REGISTERED = "NOT_REGISTERED";
    public static final String STATE_REGISTERED = "REGISTERED";
    public static final String STATE_UNREGISTERED = "UNREGISTERED";

    private static final SecureRandom RNG = new SecureRandom();

    private static final ScscfPicker DEFAULT_PICKER = (rec, impu, capabilities) ->
            (rec.imsScscfUri() != null && !rec.imsScscfUri().isBlank())
                    ? rec.imsScscfUri()
                    : DEFAULT_SCSCF;

    private final HssRepository repo;
    private final SecretStore secrets;
    private final ScscfPicker scscf;
    /** Cx peer realm for server-initiated RTR/PPR emission; default ims.lab. */
    private final String cxRealm;

    /** Server-initiated emission seam (RTR/PPR → S-CSCF); null keeps it inert. */
    private volatile DiameterSenderPort sender;

    public CxService(HssRepository repo) {
        this(repo, null, null);
    }

    public CxService(HssRepository repo, SecretStore secrets) {
        this(repo, secrets, null);
    }

    public CxService(HssRepository repo, SecretStore secrets, ScscfPicker scscf) {
        this.repo = Objects.requireNonNull(repo);
        this.secrets = secrets;
        this.scscf = scscf != null ? scscf : DEFAULT_PICKER;
        this.cxRealm = "ims.lab";
    }

    /** Attach the RA command-port bridge for server-initiated pushes. */
    public CxService withSender(DiameterSenderPort port) {
        this.sender = port;
        return this;
    }

    public String assignScscf(Imsi imsi, String scscfUri) {
        repo.setImsScscf(imsi, scscfUri);
        return repo.find(imsi).map(SubscriberRecord::imsScscfUri).orElse(scscfUri);
    }

    public String locatedScscf(Imsi imsi) {
        return repo.find(imsi).map(SubscriberRecord::imsScscfUri).orElse(null);
    }

    /** UAR → UAA. Picks an S-CSCF via the configured {@link ScscfPicker}. */
    public CxResult uar(String impi, String impu) {
        return uar(impi, impu, Map.of());
    }

    public CxResult uar(String impi, String impu, Map<Integer, String> serverCapabilities) {
        SubscriberRecord rec = resolve(impi, impu);
        if (rec == null || !rec.profile().enabled()) {
            return CxResult.error(CxAvp.ERROR_USER_UNKNOWN);
        }
        String server = scscf.pick(rec, impu, serverCapabilities);
        if (server == null || server.isBlank()) {
            return CxResult.error(CxAvp.UNABLE_TO_COMPLY);
        }
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, username(impi, rec));
        avps.put(CxAvp.SERVER_NAME, server);
        // Open5GS S-CSCF parity: UAA uses the 3GPP experimental app codes, not
        // DIAMETER_SUCCESS — FIRST_REGISTRATION once no S-CSCF is yet bound,
        // SUBSEQUENT_REGISTRATION on re-registration.
        long result = rec.imsScscfUri() == null || rec.imsScscfUri().isBlank()
                ? CxAvp.FIRST_REGISTRATION
                : CxAvp.SUBSEQUENT_REGISTRATION;
        List<DiameterAvp> structured = List.of(
                DiameterAvp.enum32(CxAvp.AUTH_SESSION_STATE, 1L));
        return CxResult.of(result, avps, structured);
    }

    /**
     * MAR → MAA. Returns IMS AKA (not EPS) vectors: Milenage RES/CK/IK with the
     * AKAv1-MD5 digest scheme (TS 33.203 §6.1.2, RFC 3310 name
     * {@value CxAvp#SCHEME_AKA_V1_MD5}) in SIP-Authentication-Scheme(608) — both in
     * the flat AVP map and inside the grouped SIP-Auth-Data-Item(612). Challenge
     * SIP-Authenticate(609) = RAND‖AUTN. Never reuses EPS vectors (no KASME path).
     */
    public CxResult mar(String impi, String impu) {
        SubscriberRecord rec = resolve(impi, impu);
        if (rec == null || !rec.profile().enabled()) {
            return CxResult.error(CxAvp.ERROR_USER_UNKNOWN);
        }
        if (secrets == null) {
            return CxResult.error(CxAvp.ERROR_AUTHORIZATION_REJECTED);
        }
        byte[] ki = secrets.ki(rec.imsi());
        byte[] opc = secrets.opc(rec.imsi());
        byte[] amf = secrets.amf(rec.imsi());
        long usedSqn = repo.reserveSqn(rec.imsi());
        byte[] rand = random16();
        byte[] sqn6 = Bytes.sqnToBytes(usedSqn);
        Milenage.Result m = Milenage.compute(ki, opc, rand, sqn6, amf);
        byte[] autn = Bytes.concat(Bytes.xor(sqn6, m.ak()), amf, m.macA());

        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, username(impi, rec));
        avps.put(CxAvp.SIP_NUMBER_AUTH_ITEMS, "1");
        avps.put(CxAvp.SIP_AUTHENTICATION_SCHEME, CxAvp.SCHEME_AKA_V1_MD5);
        avps.put(CxAvp.SIP_AUTHENTICATE, Bytes.toHex(Bytes.concat(rand, autn)));
        avps.put(CxAvp.SIP_AUTHORIZATION, Bytes.toHex(m.res()));
        avps.put(CxAvp.CONFIDENTIALITY_KEY, Bytes.toHex(m.ck()));
        avps.put(CxAvp.INTEGRITY_KEY, Bytes.toHex(m.ik()));

        // Open5GS S-CSCF consumes the grouped SIP-Auth-Data-Item(612) with 3GPP-vendor leaves,
        // not the flat map above.
        String publicIdentity = username(impi, rec);
        List<DiameterAvp> structured = new ArrayList<>();
        structured.add(DiameterAvp.grouped3gpp(CxAvp.SIP_AUTH_DATA_ITEM, List.of(
                DiameterAvp.utf8(CxAvp.SIP_AUTHENTICATION_SCHEME, DiameterAvp.VENDOR_3GPP,
                        CxAvp.SCHEME_AKA_V1_MD5, true),
                DiameterAvp.utf8(CxAvp.SIP_AUTHENTICATE, DiameterAvp.VENDOR_3GPP,
                        Bytes.toHex(Bytes.concat(rand, autn)), true),
                DiameterAvp.utf8(CxAvp.SIP_AUTHORIZATION, DiameterAvp.VENDOR_3GPP,
                        Bytes.toHex(m.res()), true),
                DiameterAvp.octets(CxAvp.CONFIDENTIALITY_KEY, DiameterAvp.VENDOR_3GPP, m.ck(), true),
                DiameterAvp.octets(CxAvp.INTEGRITY_KEY, DiameterAvp.VENDOR_3GPP, m.ik(), true))));
        structured.add(DiameterAvp.utf8(CxAvp.PUBLIC_IDENTITY, DiameterAvp.VENDOR_3GPP,
                publicIdentity, true));
        if (rec.imsScscfUri() != null && !rec.imsScscfUri().isBlank()) {
            structured.add(DiameterAvp.utf8(CxAvp.SERVER_NAME, DiameterAvp.VENDOR_3GPP,
                    rec.imsScscfUri(), true));
        }
        structured.add(DiameterAvp.enum32(CxAvp.AUTH_SESSION_STATE, 1L));
        return CxResult.ok(avps, structured);
    }

    /** SAR → SAA. Assigns (or clears) S-CSCF and returns service-profile/iFC as User-Data. */
    public CxResult sar(String impi) {
        return sar(impi, null, null, CxAvp.SAT_REGISTRATION);
    }

    public CxResult sar(String impi, String impu, String serverName, int assignmentType) {
        SubscriberRecord rec = resolve(impi, impu);
        if (rec == null || !rec.profile().enabled()) {
            return CxResult.error(CxAvp.ERROR_USER_UNKNOWN);
        }
        applyAssignment(rec, serverName, assignmentType);
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, username(impi, rec));
        put(avps, CxAvp.SERVER_NAME, rec.imsScscfUri());
        put(avps, CxAvp.USER_DATA, rec.serviceProfileJson());

        // Open5GS S-CSCF expects Charging-Information(618) with the CCF names and
        // Public-Identity — grouped, 3GPP vendor, consumed from structuredAvps.
        DiameterAvp charging = DiameterAvp.grouped3gpp(CxAvp.CHARGING_INFORMATION, List.of(
                DiameterAvp.utf8(CxAvp.PRIMARY_CHARGING_COLLECTION_FUNCTION_NAME,
                        DiameterAvp.VENDOR_3GPP, PRIMARY_CCF, true),
                DiameterAvp.utf8(CxAvp.SECONDARY_CHARGING_COLLECTION_FUNCTION_NAME,
                        DiameterAvp.VENDOR_3GPP, SECONDARY_CCF, true)));
        String publicIdentity = impu != null && !impu.isBlank() ? impu : username(impi, rec);
        List<DiameterAvp> structured = List.of(
                DiameterAvp.enum32(CxAvp.AUTH_SESSION_STATE, 1L),
                charging,
                DiameterAvp.utf8(CxAvp.PUBLIC_IDENTITY, DiameterAvp.VENDOR_3GPP,
                        publicIdentity, true));
        return CxResult.ok(avps, structured);
    }

    /** LIR → LIA. Returns the located S-CSCF for an IMPU. */
    public CxResult lir(String impu) {
        SubscriberRecord rec = impu != null && !impu.isBlank()
                ? repo.findByImpu(impu).orElse(null)
                : null;
        if (rec == null || !rec.profile().enabled()) {
            return CxResult.error(CxAvp.ERROR_USER_UNKNOWN);
        }
        // Registration-state aware location: the S-CSCF stored by SAR (REGISTERED or
        // UNREGISTERED with retained server-name); null once deregistered/RTR.
        String server = repo.locateScscf(rec.imsi());
        if (server == null || server.isBlank()) {
            server = scscf.pick(rec, impu, Map.of());
        }
        if (server == null || server.isBlank()) {
            return CxResult.error(CxAvp.ERROR_USER_UNKNOWN);
        }
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        avps.put(CxAvp.SERVER_NAME, server);
        return CxResult.ok(avps, List.of(
                DiameterAvp.enum32(CxAvp.AUTH_SESSION_STATE, 1L)));
    }

    /**
     * RTR (Registration-Termination-Request) request AVPs (HSS → S-CSCF). Terminating
     * the registration also flips the IMPU back to {@code NOT_REGISTERED} and clears
     * the stored S-CSCF (mirrors the SAR deregistration transitions).
     */
    public CxResult rtr(String impi, String impu, int deregistrationReason) {
        SubscriberRecord rec = resolve(impi, impu);
        if (rec != null) {
            deregister(rec);
        }
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, impi);
        put(avps, CxAvp.PUBLIC_IDENTITY, impu);
        avps.put(CxAvp.DEREGISTRATION_REASON, Integer.toString(deregistrationReason));
        return CxResult.ok(avps);
    }

    /** RTA (Registration-Termination-Answer) ack. */
    public CxResult rta() {
        return CxResult.ok(Map.of(), List.of(
                DiameterAvp.enum32(CxAvp.AUTH_SESSION_STATE, 1L)));
    }

    /** PPR (Push-Profile-Request) request AVPs (User-Data push / iFC update). */
    public CxResult ppr(String impi, String userData) {
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, impi);
        put(avps, CxAvp.USER_DATA, userData);
        return CxResult.ok(avps);
    }

    /** PPA (Push-Profile-Answer) ack. */
    public CxResult ppa() {
        return CxResult.ok(Map.of(), List.of(
                DiameterAvp.enum32(CxAvp.AUTH_SESSION_STATE, 1L)));
    }

    /* ------------------------------------------------------------------ *
     * Server-initiated Cx (HSS → S-CSCF): RTR / PPR, emitted as
     * SendDiameterRequest through the seam. Destination = bound S-CSCF.
     * ------------------------------------------------------------------ */

    /** Registration-Termination-Request to the bound S-CSCF of the subscriber. */
    public SendDiameterRequest rtrRequest(String impi, String impu, int deregReason) {
        SubscriberRecord rec = resolve(impi, impu);
        Map<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, impi);
        if (impu != null && !impu.isBlank()) {
            avps.put(CxAvp.PUBLIC_IDENTITY, impu);
        }
        avps.put(CxAvp.DEREGISTRATION_REASON, Integer.toString(deregReason));
        return new SendDiameterRequest(sessionId(), CxAvp.APPLICATION_ID, CxAvp.RTR,
                scscfHost(rec), cxRealm, avps);
    }

    public void pushRtr(String impi, String impu, int deregReason) {
        // Build the request first — it must target the S-CSCF still bound to the IMPU —
        // then flip the IMPU to NOT_REGISTERED and clear the stored server.
        SendDiameterRequest request = rtrRequest(impi, impu, deregReason);
        SubscriberRecord rec = resolve(impi, impu);
        if (rec != null) {
            deregister(rec);
        }
        DiameterSenderPort p = sender;
        if (p != null) {
            p.send(request);
        }
    }

    /** Push-Profile-Request to the bound S-CSCF, carrying the new User-Data (iFC). */
    public SendDiameterRequest pprRequest(String impi, String impu, String userData) {
        SubscriberRecord rec = resolve(impi, impu);
        Map<Integer, String> avps = new LinkedHashMap<>();
        put(avps, CxAvp.USER_NAME, impi);
        put(avps, CxAvp.USER_DATA, userData);
        String publicIdentity = (impu != null && !impu.isBlank())
                ? impu : (rec != null && rec.impi() != null ? rec.impi() : impi);
        List<DiameterAvp> structured = List.of(
                DiameterAvp.utf8(CxAvp.PUBLIC_IDENTITY, DiameterAvp.VENDOR_3GPP,
                        publicIdentity, true),
                DiameterAvp.utf8(CxAvp.USER_DATA, DiameterAvp.VENDOR_3GPP,
                        userData == null ? "" : userData, true));
        return new SendDiameterRequest(sessionId(), CxAvp.APPLICATION_ID, CxAvp.PPR,
                scscfHost(rec), cxRealm, avps, structured);
    }

    public void pushPpr(String impi, String impu, String userData) {
        DiameterSenderPort p = sender;
        if (p != null) {
            p.send(pprRequest(impi, impu, userData));
        }
    }

    /** S-CSCF host (server-name minus any {@code sip:} prefix), or the realm default. */
    private static String scscfHost(SubscriberRecord rec) {
        if (rec == null || rec.imsScscfUri() == null || rec.imsScscfUri().isBlank()) {
            return null;
        }
        String uri = rec.imsScscfUri().trim();
        if (uri.startsWith("sip:")) {
            uri = uri.substring("sip:".length());
        }
        return uri;
    }

    private static String sessionId() {
        return "cx-init;" + UUID.randomUUID();
    }

    /**
     * Structured Cx answer the lead's DiameterGatewaySbb maps to
     * {@code SendDiameterAnswer}: resultCode stays consistent with Result-Code (268),
     * and {@code structuredAvps} carries the grouped SIP-Auth-Data-Item /
     * Charging-Information AVPs consumed by an Open5GS S-CSCF.
     */
    public record CxResult(long resultCode, Map<Integer, String> avps,
                           List<DiameterAvp> structuredAvps) {
        public CxResult {
            avps = Map.copyOf(avps == null ? Map.of() : avps);
            structuredAvps = structuredAvps == null
                    ? List.of() : List.copyOf(structuredAvps);
        }

        public CxResult(long resultCode, Map<Integer, String> avps) {
            this(resultCode, avps, List.of());
        }

        public static CxResult of(long resultCode, Map<Integer, String> avps) {
            LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
            if (avps != null) {
                m.putAll(avps);
            }
            m.put(CxAvp.RESULT_CODE, Long.toString(resultCode));
            return new CxResult(resultCode, m);
        }

        public static CxResult of(long resultCode, Map<Integer, String> avps,
                                  List<DiameterAvp> structuredAvps) {
            LinkedHashMap<Integer, String> m = new LinkedHashMap<>();
            if (avps != null) {
                m.putAll(avps);
            }
            m.put(CxAvp.RESULT_CODE, Long.toString(resultCode));
            return new CxResult(resultCode, m, structuredAvps);
        }

        public static CxResult ok(Map<Integer, String> avps) {
            return of(CxAvp.SUCCESS, avps);
        }

        public static CxResult ok(Map<Integer, String> avps, List<DiameterAvp> structuredAvps) {
            return of(CxAvp.SUCCESS, avps, structuredAvps);
        }

        public static CxResult error(long resultCode) {
            return of(resultCode, Map.of());
        }
    }

    /**
     * S-CSCF selection seam. A production deployment matches Server-Capabilities
     * (604/605) against an S-CSCF pool; the default is single-node (stored or
     * {@link #DEFAULT_SCSCF}).
     */
    @FunctionalInterface
    public interface ScscfPicker {
        String pick(SubscriberRecord rec, String impu, Map<Integer, String> capabilities);
    }

    private SubscriberRecord resolve(String impi, String impu) {
        if (impi != null && !impi.isBlank()) {
            SubscriberRecord rec = repo.findByImpi(impi).orElse(null);
            if (rec != null) {
                return rec;
            }
        }
        if (impu != null && !impu.isBlank()) {
            return repo.findByImpu(impu).orElse(null);
        }
        return null;
    }

    private static String username(String impi, SubscriberRecord rec) {
        if (impi != null && !impi.isBlank()) {
            return impi;
        }
        return rec.impi() != null ? rec.impi() : rec.imsi().digits();
    }

    private static boolean deregistersWithoutStore(int assignmentType) {
        return assignmentType == CxAvp.SAT_TIMEOUT_DEREGISTRATION
                || assignmentType == CxAvp.SAT_USER_DEREGISTRATION
                || assignmentType == CxAvp.SAT_ADMINISTRATIVE_DEREGISTRATION
                || assignmentType == CxAvp.SAT_AUTHENTICATION_FAILURE
                || assignmentType == CxAvp.SAT_AUTHENTICATION_TIMEOUT;
    }

    private static boolean storesServerNameWhileUnregistered(int assignmentType) {
        return assignmentType == CxAvp.SAT_TIMEOUT_DEREGISTRATION_STORE_SERVER_NAME
                || assignmentType == CxAvp.SAT_USER_DEREGISTRATION_STORE_SERVER_NAME;
    }

    /**
     * TS 29.228 IMPU registration state machine, persisted via
     * {@link HssRepository#setImpuRegState} (durable row, never heap-only):
     * <ul>
     *   <li>{@code REGISTRATION}/{@code RE_REGISTRATION} → {@code REGISTERED}, S-CSCF stored.</li>
     *   <li>{@code UNREGISTERED_USER} → server-name stored per TS 29.228 §6.3.4 while the
     *       user stays {@code UNREGISTERED} (services for unregistered users).</li>
     *   <li>{@code *_STORE_SERVER_NAME} deregistrations → {@code UNREGISTERED}, server retained.</li>
     *   <li>plain deregistrations / auth failure → {@code NOT_REGISTERED}, server cleared.</li>
     *   <li>{@code NO_ASSIGNMENT} → query only, no state change.</li>
     * </ul>
     */
    private void applyAssignment(SubscriberRecord rec, String serverName, int assignmentType) {
        boolean hasServer = serverName != null && !serverName.isBlank();
        if (assignmentType == CxAvp.SAT_REGISTRATION || assignmentType == CxAvp.SAT_RE_REGISTRATION) {
            String server = hasServer ? serverName : rec.imsScscfUri();
            if (hasServer) {
                repo.setImsScscf(rec.imsi(), serverName);
                rec.setImsScscfUri(serverName);
            }
            repo.setImpuRegState(rec.imsi(), STATE_REGISTERED, server);
            rec.setImpuRegState(STATE_REGISTERED);
            rec.setScscfUri(server);
        } else if (assignmentType == CxAvp.SAT_UNREGISTERED_USER) {
            // TS 29.228: the HSS stores the received Server-Name; the IMPU remains
            // UNREGISTERED (network-originated services for an unregistered user).
            String server = hasServer ? serverName : rec.imsScscfUri();
            if (hasServer) {
                repo.setImsScscf(rec.imsi(), serverName);
                rec.setImsScscfUri(serverName);
            }
            repo.setImpuRegState(rec.imsi(), STATE_UNREGISTERED, server);
            rec.setImpuRegState(STATE_UNREGISTERED);
            rec.setScscfUri(server);
        } else if (storesServerNameWhileUnregistered(assignmentType)) {
            // Deregistered, but the S-CSCF name is retained (store-server-name types).
            repo.setImpuRegState(rec.imsi(), STATE_UNREGISTERED, rec.imsScscfUri());
            rec.setImpuRegState(STATE_UNREGISTERED);
            rec.setScscfUri(rec.imsScscfUri());
        } else if (deregistersWithoutStore(assignmentType)) {
            deregister(rec);
        }
        // SAT_NO_ASSIGNMENT (and unknown values): no state change.
    }

    /** Deregistration (SAR dereg types and RTR) → NOT_REGISTERED, stored S-CSCF cleared. */
    private void deregister(SubscriberRecord rec) {
        repo.setImsScscf(rec.imsi(), null);
        repo.setImpuRegState(rec.imsi(), STATE_NOT_REGISTERED, null);
        rec.setImsScscfUri(null);
        rec.setImpuRegState(STATE_NOT_REGISTERED);
        rec.setScscfUri(null);
    }

    private static void put(Map<Integer, String> avps, int code, String value) {
        if (value != null && !value.isBlank()) {
            avps.put(code, value);
        }
    }

    private static byte[] random16() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return b;
    }
}
