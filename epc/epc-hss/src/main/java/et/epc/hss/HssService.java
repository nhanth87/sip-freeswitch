package et.epc.hss;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.epc.api.ApnConfig;
import et.epc.api.EpsAuthVector;
import et.epc.api.Imsi;
import et.epc.api.PdnType;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import et.epc.crypto.Bytes;
import et.epc.crypto.EpsAka;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Standalone HSS/AuC — S6a procedures. SQN is reserved atomically on the subscriber
 * row before AIA is returned (not a heap-only pending vector).
 *
 * <p>Peer trust: ULR/AIR are only served when the request Origin-Host / Origin-Realm /
 * Visited-PLMN-Id match {@link MmeAllowlist} — otherwise {@link S6aAvp#ERROR_UNKNOWN_PEER}
 * (3002) is returned and the serving-MME binding is never touched (closes Open5GS #4337).
 * Server-initiated moves (CLR / PUR / IDR) are emitted through {@link DiameterSenderPort}.</p>
 */
public final class HssService {

    /** TS 29.272 Subscriber-Status: SERVICE_GRANTED(0) — no operator barring. */
    private static final long SUBSCRIBER_STATUS_SERVICE_GRANTED = 0L;
    /** TS 29.272 Network-Access-Mode: ONLY_PACKET(2) — EPS-only, no CS fallback. */
    private static final long NETWORK_ACCESS_MODE_ONLY_PACKET = 2L;
    /** Subscribed periodic RAU/TAU timer in seconds (Open5GS default 12 min, TS 29.272 7.3.134). */
    private static final long SUBSCRIBED_RAU_TAU_TIMER_SECONDS = 12L * 60L;

    private final HssRepository repo;
    private final SecretStore secrets;
    private final PlmnId homePlmn;
    private final MmeAllowlist allowlist;
    private final String mmeRealm;

    /** Server-initiated emission seam; {@code null} keeps the procedure inert (no RA bound). */
    private volatile DiameterSenderPort sender;

    public HssService(HssRepository repo, SecretStore secrets, PlmnId homePlmn) {
        this(repo, secrets, homePlmn, MmeAllowlist.disabled(), "mme.epc.lab");
    }

    public HssService(HssRepository repo, SecretStore secrets, PlmnId homePlmn,
                      MmeAllowlist allowlist, String mmeRealm) {
        this.repo = Objects.requireNonNull(repo);
        this.secrets = Objects.requireNonNull(secrets);
        this.homePlmn = Objects.requireNonNull(homePlmn);
        this.allowlist = allowlist == null ? MmeAllowlist.disabled() : allowlist;
        this.mmeRealm = (mmeRealm == null || mmeRealm.isBlank()) ? "mme.epc.lab" : mmeRealm;
    }

    /** Attach the RA command-port bridge (or {@code null} to detach). */
    public HssService withSender(DiameterSenderPort port) {
        this.sender = port;
        return this;
    }

    public MmeAllowlist allowlist() {
        return allowlist;
    }

    public String mmeRealm() {
        return mmeRealm;
    }

    public S6aMessage handle(S6aMessage req) {
        // Missing or malformed User-Name is a protocol error (Experimental-Result MISSING_AVP /
        // INVALID_AVP_VALUE), never a Java exception escaping into the SLEE event dispatch.
        String userName = req.avpUtf8(S6aAvp.USER_NAME);
        if (userName == null || userName.isBlank()) {
            return experimentalError(req.command(), S6aAvp.MISSING_AVP);
        }
        try {
            new Imsi(userName);
        } catch (IllegalArgumentException malformed) {
            return experimentalError(req.command(), S6aAvp.INVALID_AVP_VALUE);
        }
        return switch (req.command()) {
            case AIR -> air(req);
            case ULR -> ulr(req);
            case CLR -> clr(req);
            case PUR -> pur(req);
            case IDR -> ok(S6aCommand.IDA, imsiOf(req));
            case DSR -> dsr(req);
            case RSR -> ok(S6aCommand.RSA, imsiOf(req));
            case NOR -> ok(S6aCommand.NOA, imsiOf(req));
            default -> experimentalError(req.command(), S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE);
        };
    }

    private S6aMessage air(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        if (!peerAllowed(req)) {
            return experimentalError(S6aCommand.AIR, S6aAvp.ERROR_UNKNOWN_PEER);
        }
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        if (rec == null || !rec.profile().enabled()) {
            return experimentalError(S6aCommand.AIA, S6aAvp.ERROR_USER_UNKNOWN);
        }
        // Resync (SQN/AUTS) path — ra-diameter now decodes RAND + Re-Synchronization-Info
        // as structured octet AVPs; prefer those, fall back to the flat map.
        byte[] auts = req.structuredBytes(S6aAvp.RE_SYNCHRONIZATION_INFO);
        if (auts == null) {
            auts = req.avp(S6aAvp.RE_SYNCHRONIZATION_INFO);
        }
        if (auts != null && auts.length >= 14) {
            byte[] rand = req.structuredBytes(S6aAvp.RAND);
            if (rand == null) {
                rand = req.avp(S6aAvp.RAND);
            }
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
                .structured(aiaStructured(v))
                .build();
    }

    /**
     * 3GPP-grouped AIA vector: {@code Authentication-Info(1490) -> E-UTRAN-Vector(1491)
     * -> {RAND, XRES, AUTN, KASME}}, plus base Auth-Session-State(277)=NO_STATE_MAINTAINED.
     */
    private static List<DiameterAvp> aiaStructured(EpsAuthVector v) {
        DiameterAvp vector = DiameterAvp.grouped3gpp(S6aAvp.E_UTRAN_VECTOR, List.of(
                DiameterAvp.octets(S6aAvp.RAND, DiameterAvp.VENDOR_3GPP, v.rand(), true),
                DiameterAvp.octets(S6aAvp.XRES, DiameterAvp.VENDOR_3GPP, v.xres(), true),
                DiameterAvp.octets(S6aAvp.AUTN, DiameterAvp.VENDOR_3GPP, v.autn(), true),
                DiameterAvp.octets(S6aAvp.KASME, DiameterAvp.VENDOR_3GPP, v.kasme(), true)));
        return List.of(
                DiameterAvp.grouped3gpp(S6aAvp.AUTHENTICATION_INFO, List.of(vector)),
                DiameterAvp.enum32(S6aAvp.AUTH_SESSION_STATE, 1L));
    }

    private S6aMessage ulr(S6aMessage req) {
        Imsi imsi = imsiOf(req);
        if (!peerAllowed(req)) {
            // Never touch the serving-MME binding for a peer outside the allowlist
            // (closes Open5GS #4337 — a rogue MME ULR must not take over the binding).
            return experimentalError(S6aCommand.ULR, S6aAvp.ERROR_UNKNOWN_PEER);
        }
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        if (rec == null || !rec.profile().enabled()) {
            return experimentalError(S6aCommand.ULA, S6aAvp.ERROR_USER_UNKNOWN);
        }
        String old = rec.servingMmeHost();
        String neu = req.avpUtf8(S6aAvp.ORIGIN_HOST);
        repo.setServingMme(imsi, neu);
        repo.setPurged(imsi, false);
        S6aMessage.Builder b = S6aMessage.builder(S6aCommand.ULA, false)
                .putUtf8(S6aAvp.USER_NAME, imsi.digits())
                .put(S6aAvp.RESULT_CODE, u32(S6aAvp.SUCCESS));
        S6aSubscriptionData.ulaAvps(rec.profile())
                .forEach((code, value) -> b.putUtf8(code, value));
        if (old != null && neu != null && !old.equals(neu)) {
            b.putUtf8(S6aAvp.CANCELLATION_TYPE, "MME_UPDATE_PROCEDURE:" + old);
        }
        b.structured(ulaStructured(rec.profile(), req));
        return b.build();
    }

    /**
     * 3GPP-grouped ULA body: full {@code Subscription-Data(1400)} with
     * Subscriber-Status(1424), MSISDN(701 TBCD), Network-Access-Mode(1417),
     * Operator-Determined-Barring(1425), Access-Restriction-Data(1426), UE-AMBR(1435),
     * APN-Configuration-Profile(1429){Context-Identifier(1423) + APN-Configuration(1430)*}
     * and Subscribed-Periodic-RAU-TAU-Timer(1619), plus ULA-Flags(1406),
     * Visited-PLMN-Id(1407) and base Auth-Session-State(277) at the answer top level.
     */
    private List<DiameterAvp> ulaStructured(SubscriberProfile profile, S6aMessage req) {
        byte[] visited = req.avp(S6aAvp.VISITED_PLMN_ID);
        if (visited == null || visited.length != 3) {
            visited = homePlmn.snId();
        }
        return List.of(
                subscriptionDataStructured(profile),
                DiameterAvp.u32(S6aAvp.ULA_FLAGS, DiameterAvp.VENDOR_3GPP, 0L, true),
                DiameterAvp.octets(S6aAvp.VISITED_PLMN_ID, DiameterAvp.VENDOR_3GPP, visited, true),
                DiameterAvp.enum32(S6aAvp.AUTH_SESSION_STATE, 1L));
    }

    /** Full differentiated Subscription-Data(1400) consumed by a real MME (TS 29.272 7.3.2). */
    private static DiameterAvp subscriptionDataStructured(SubscriberProfile profile) {
        QosProfile q = profile.subscribedQos();
        List<DiameterAvp> children = new ArrayList<>();
        children.add(DiameterAvp.enum32(S6aAvp.SUBSCRIBER_STATUS,
                DiameterAvp.VENDOR_3GPP, SUBSCRIBER_STATUS_SERVICE_GRANTED, true));
        children.add(DiameterAvp.octets(S6aAvp.MSISDN, DiameterAvp.VENDOR_3GPP,
                tbcd(profile.msisdn()), true));
        children.add(DiameterAvp.enum32(S6aAvp.NETWORK_ACCESS_MODE,
                DiameterAvp.VENDOR_3GPP, NETWORK_ACCESS_MODE_ONLY_PACKET, true));
        children.add(DiameterAvp.u32(S6aAvp.OPERATOR_DETERMINED_BARRING,
                DiameterAvp.VENDOR_3GPP, 0L, true));
        children.add(DiameterAvp.u32(S6aAvp.ACCESS_RESTRICTION_DATA,
                DiameterAvp.VENDOR_3GPP, 0L, true));
        children.add(ueAmbr(q));
        children.add(apnConfigurationProfile(profile));
        children.add(DiameterAvp.u32(S6aAvp.SUBSCRIBED_PERIODIC_RAU_TAU_TIMER,
                DiameterAvp.VENDOR_3GPP, SUBSCRIBED_RAU_TAU_TIMER_SECONDS, true));
        return DiameterAvp.grouped3gpp(S6aAvp.SUBSCRIPTION_DATA, children);
    }

    private static DiameterAvp ueAmbr(QosProfile q) {
        return DiameterAvp.grouped3gpp(S6aAvp.AMBR, List.of(
                DiameterAvp.u32(S6aAvp.MAX_REQUESTED_BANDWIDTH_UL,
                        DiameterAvp.VENDOR_3GPP, q.ueAmbrUlBps(), true),
                DiameterAvp.u32(S6aAvp.MAX_REQUESTED_BANDWIDTH_DL,
                        DiameterAvp.VENDOR_3GPP, q.ueAmbrDlBps(), true)));
    }

    private static DiameterAvp apnConfigurationProfile(SubscriberProfile profile) {
        List<DiameterAvp> children = new ArrayList<>();
        children.add(DiameterAvp.u32(S6aAvp.CONTEXT_IDENTIFIER, DiameterAvp.VENDOR_3GPP, 1L, true));
        children.add(DiameterAvp.enum32(S6aAvp.ALL_APN_CONFIGURATIONS_INCLUDED_INDICATOR,
                DiameterAvp.VENDOR_3GPP, 0L, true));
        int contextId = 1;
        for (ApnConfig apn : profile.apns()) {
            children.add(apnConfiguration(apn, contextId++));
        }
        return DiameterAvp.grouped3gpp(S6aAvp.APN_CONFIGURATION_PROFILE, children);
    }

    private static DiameterAvp apnConfiguration(ApnConfig apn, int contextId) {
        return DiameterAvp.grouped3gpp(S6aAvp.APN_CONFIGURATION, List.of(
                DiameterAvp.u32(S6aAvp.CONTEXT_IDENTIFIER, DiameterAvp.VENDOR_3GPP, contextId, true),
                DiameterAvp.enum32(S6aAvp.PDN_TYPE, DiameterAvp.VENDOR_3GPP, pdnType(apn.pdnType()), true),
                DiameterAvp.utf8(S6aAvp.SERVICE_SELECTION, 0L, apn.apn(), true),
                epsSubscribedQos(apn.qos()),
                apnAmbr(apn.qos())));
    }

    private static DiameterAvp epsSubscribedQos(QosProfile q) {
        DiameterAvp arp = DiameterAvp.grouped3gpp(S6aAvp.ALLOCATION_RETENTION_PRIORITY, List.of(
                DiameterAvp.u32(S6aAvp.PRIORITY_LEVEL, DiameterAvp.VENDOR_3GPP, q.arpPriority(), true),
                DiameterAvp.enum32(S6aAvp.PRE_EMPTION_CAPABILITY, DiameterAvp.VENDOR_3GPP,
                        q.preemptionCapability() ? 1L : 0L, true),
                DiameterAvp.enum32(S6aAvp.PRE_EMPTION_VULNERABILITY, DiameterAvp.VENDOR_3GPP,
                        q.preemptionVulnerability() ? 1L : 0L, true)));
        return DiameterAvp.grouped3gpp(S6aAvp.EPS_SUBSCRIBED_QOS_PROFILE, List.of(
                DiameterAvp.enum32(S6aAvp.QOS_CLASS_IDENTIFIER, DiameterAvp.VENDOR_3GPP, q.qci(), true),
                arp));
    }

    private static DiameterAvp apnAmbr(QosProfile q) {
        return DiameterAvp.grouped3gpp(S6aAvp.AMBR, List.of(
                DiameterAvp.u32(S6aAvp.MAX_REQUESTED_BANDWIDTH_UL,
                        DiameterAvp.VENDOR_3GPP, q.apnAmbrUlBps(), true),
                DiameterAvp.u32(S6aAvp.MAX_REQUESTED_BANDWIDTH_DL,
                        DiameterAvp.VENDOR_3GPP, q.apnAmbrDlBps(), true)));
    }

    private static long pdnType(PdnType type) {
        return switch (type) {
            case IPV4 -> 0L;
            case IPV6 -> 1L;
            case IPV4V6 -> 2L;
        };
    }

    /** Encode an MSISDN digit string as TBCD octets (TS 23.003), odd length gets 0xF pad. */
    private static byte[] tbcd(String msisdn) {
        StringBuilder s = new StringBuilder(msisdn == null ? "" : msisdn);
        if (s.length() % 2 == 1) {
            s.append('F');
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int lo = digit(s.charAt(2 * i));
            char hi = s.charAt(2 * i + 1);
            out[i] = (byte) (((hi == 'F' ? 0xF : digit(hi)) << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        if (c < '0' || c > '9') {
            throw new IllegalArgumentException("non-TBCD digit " + c);
        }
        return c - '0';
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
                .structured(List.of(DiameterAvp.enum32(S6aAvp.AUTH_SESSION_STATE, 1L)))
                .build();
    }

    /**
     * Build an error answer carrying an Experimental-Result code (TS 29.272): the code is
     * placed in the flat {@code Result-Code(268)} slot so {@code S6aDiameterMapper} routes it
     * to {@code SendDiameterAnswer.resultCode} (the RA turns 4xxx/5xxx into Experimental-Result).
     * Auth-Session-State(277)=NO_STATE_MAINTAINED is emitted on every answer, error paths included.
     */
    private S6aMessage experimentalError(S6aCommand cmd, long code) {
        S6aCommand ans = switch (cmd) {
            case AIR -> S6aCommand.AIA;
            case ULR -> S6aCommand.ULA;
            case CLR -> S6aCommand.CLA;
            case PUR -> S6aCommand.PUA;
            case IDR -> S6aCommand.IDA;
            case DSR -> S6aCommand.DSA;
            case RSR -> S6aCommand.RSA;
            case NOR -> S6aCommand.NOA;
            default -> cmd;
        };
        return S6aMessage.builder(ans, false)
                .put(S6aAvp.RESULT_CODE, u32(code))
                .structured(List.of(DiameterAvp.enum32(S6aAvp.AUTH_SESSION_STATE, 1L)))
                .build();
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
        return decodePlmn(v);
    }

    private static PlmnId decodePlmn(byte[] v) {
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

    /** True when the request's Origin-Host/Realm and Visited-PLMN are trusted peers. */
    private boolean peerAllowed(S6aMessage req) {
        String originHost = req.avpUtf8(S6aAvp.ORIGIN_HOST);
        String originRealm = req.avpUtf8(S6aAvp.ORIGIN_REALM);
        // Peer-trust must NOT default an absent Visited-PLMN to the home PLMN: doing so
        // would let any origin pass just because the home PLMN is allowlisted. Only an
        // explicit Visited-PLMN-Id participates in the drawbridge.
        byte[] visited = req.avp(S6aAvp.VISITED_PLMN_ID);
        PlmnId visitedPlmn = (visited == null || visited.length != 3) ? null : decodePlmn(visited);
        return allowlist.allows(originHost, originRealm, visitedPlmn);
    }

    /* ------------------------------------------------------------------ *
     * Server-initiated S6a (HSS → MME): CLR / PUR / IDR.
     * Built as SendDiameterRequest and pushed through the RA command-port
     * seam (DiameterSenderPort) when one is wired.
     * ------------------------------------------------------------------ */

    /** Cancel-Location-Request to the serving MME for the given subscriber. */
    public SendDiameterRequest clrRequest(Imsi imsi) {
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        Map<Integer, String> avps = new LinkedHashMap<>();
        avps.put(S6aAvp.USER_NAME, imsi.digits());
        avps.put(S6aAvp.CANCELLATION_TYPE, "2"); // 2 = SUBSCRIPTION_WITHDRAWAL (TS 29.272)
        return new SendDiameterRequest(sessionId(), S6aCommand.APP_ID, S6aCommand.CLR.code,
                destinationHost(rec), mmeRealm, avps);
    }

    public void pushClr(Imsi imsi) {
        DiameterSenderPort p = sender;
        if (p != null) {
            p.send(clrRequest(imsi));
        }
    }

    /** Purge-UE-Request to the serving MME. */
    public SendDiameterRequest purRequest(Imsi imsi) {
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        Map<Integer, String> avps = new LinkedHashMap<>();
        avps.put(S6aAvp.USER_NAME, imsi.digits());
        avps.put(S6aAvp.PURGE_UE, "0");
        return new SendDiameterRequest(sessionId(), S6aCommand.APP_ID, S6aCommand.PUR.code,
                destinationHost(rec), mmeRealm, avps);
    }

    public void pushPur(Imsi imsi) {
        DiameterSenderPort p = sender;
        if (p != null) {
            p.send(purRequest(imsi));
        }
    }

    /**
     * Insert-Subscriber-Data-Request to the serving MME, carrying the full grouped
     * Subscription-Data (MSISDN, AMBR, APN-Configuration-Profile, …) as structured AVPs.
     */
    public SendDiameterRequest idrRequest(Imsi imsi) {
        SubscriberRecord rec = repo.find(imsi).orElse(null);
        Map<Integer, String> avps = new LinkedHashMap<>();
        avps.put(S6aAvp.USER_NAME, imsi.digits());
        avps.put(S6aAvp.IDR_FLAGS, "0");
        List<DiameterAvp> structured = rec == null
                ? List.of()
                : List.of(idrSubscriptionData(rec));
        return new SendDiameterRequest(sessionId(), S6aCommand.APP_ID, S6aCommand.IDR.code,
                destinationHost(rec), mmeRealm, avps, structured);
    }

    public void pushIdr(Imsi imsi) {
        DiameterSenderPort p = sender;
        if (p != null) {
            p.send(idrRequest(imsi));
        }
    }

    /**
     * Grouped Subscription-Data(1400) for IDR — reuses the same full differentiated
     * ULA builder (MSISDN, AMBR, APN-Configuration-Profile, Subscriber-Status, …),
     * so MME-change data and ULA data never drift.
     */
    private DiameterAvp idrSubscriptionData(SubscriberRecord rec) {
        return subscriptionDataStructured(rec.profile());
    }

    private static String destinationHost(SubscriberRecord rec) {
        if (rec == null || rec.servingMmeHost() == null || rec.servingMmeHost().isBlank()) {
            return null;
        }
        return rec.servingMmeHost().trim();
    }

    private static String sessionId() {
        return "hss-init;" + UUID.randomUUID();
    }

    public PlmnId homePlmn() {
        return homePlmn;
    }
}
