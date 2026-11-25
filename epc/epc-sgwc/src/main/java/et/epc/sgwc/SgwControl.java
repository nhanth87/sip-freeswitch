package et.epc.sgwc;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.gtpv2c.GtpFteid;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.GtpEchoCommand;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpSessionDeletion;
import com.microjainslee.ra.pfcp.command.PfcpSessionEstablishment;
import com.microjainslee.ra.pfcp.command.PfcpSessionModification;
import et.epc.api.ApnConfig;
import et.epc.api.BearerId;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.SessionKeys;
import et.epc.api.UeAddresses;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.SessionCheckpoint;
import et.epc.api.cluster.SessionStore;
import et.epc.api.cluster.TeidAllocator;
import et.epc.pcef.ChargingId;
import et.epc.pcef.PolicyDecision;
import et.epc.pgwc.CreateSessionResult;
import et.epc.pgwc.PgwControl;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SGW-C: S11 procedures, mobility anchor, Sxa PFCP. Default bearer is ack'd
 * only after PGW-C IP allocation and both UP rule sets are programmed.
 *
 * <p>S5-C can run either in-process (lab fallback, {@code epc.s5c.wire=false})
 * or on the wire (split SGW-C/PGW-C, {@code epc.s5c.wire=true}). In wire mode the S11
 * procedure is two-phase ({@link #beginCreateSession} → {@link #onS5cCreateResponse} /
 * {@link #onS5cTimeout}); nothing waits on the SBB thread.
 */
public final class SgwControl {

    private static final Logger LOG = LogManager.getLogger(SgwControl.class);

    private final EpcClusterConfig cluster;
    private final TeidAllocator teids;
    private final SessionStore sessions;
    private final PgwControl pgw;
    private final RaCommandPort pfcp;
    private final RaCommandPort gtp;
    private final boolean wire;
    private final InetSocketAddress pgwcPeer;
    private final InetAddress localS11;
    private final InetAddress localS1u;
    private final InetSocketAddress sgwu;
    private final AtomicLong seid;
    private final AtomicLong generation = new AtomicLong(1);
    private final AtomicInteger s5cSequence = new AtomicInteger(1);
    /** Pending S5-C procedures keyed by our S5-C TEID (the response's header TEID). */
    private final ConcurrentHashMap<Integer, PendingCreate> pendingCreates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PendingDelete> pendingDeletes = new ConcurrentHashMap<>();
    /** S5-C sequence → S5-C TEID, for the RA's procedure timeout (keyed on sequence). */
    private final ConcurrentHashMap<Integer, Integer> pendingBySeq = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, ControlPeers> controlPeers = new ConcurrentHashMap<>();

    public SgwControl(
            EpcClusterConfig cluster,
            SessionStore sessions,
            PgwControl pgw,
            RaCommandPort pfcp,
            InetAddress localS11,
            InetAddress localS1u,
            InetSocketAddress sgwu) {
        this(cluster, sessions, pgw, pfcp, localS11, localS1u, sgwu, null, false, null);
    }

    public SgwControl(
            EpcClusterConfig cluster,
            SessionStore sessions,
            PgwControl pgw,
            RaCommandPort pfcp,
            InetAddress localS11,
            InetAddress localS1u,
            InetSocketAddress sgwu,
            RaCommandPort gtp,
            boolean wire,
            InetSocketAddress pgwcPeer) {
        this.cluster = Objects.requireNonNull(cluster);
        this.teids = new TeidAllocator(cluster.nodeIndex());
        this.sessions = Objects.requireNonNull(sessions);
        this.pgw = Objects.requireNonNull(pgw);
        this.pfcp = Objects.requireNonNull(pfcp);
        this.gtp = gtp;
        this.wire = wire;
        this.pgwcPeer = pgwcPeer;
        this.localS11 = Objects.requireNonNull(localS11);
        this.localS1u = Objects.requireNonNull(localS1u);
        this.sgwu = Objects.requireNonNull(sgwu);
        this.seid = new AtomicLong((((long) cluster.nodeIndex()) << 32) | 1_000L);
    }

    public SgwCreateSessionResult createSession(Imsi imsi, ApnConfig apn, Fteid enbS1u) {
        return createSession(imsi, apn, enbS1u, null);
    }

    /**
     * In-process S5 (lab, {@code epc.s5c.wire=false}): the co-located PGW-C answers at once.
     * {@code enbS1u} may be null — a Create Session carries no eNB S1-U F-TEID; Modify Bearer does.
     */
    public SgwCreateSessionResult createSession(
            Imsi imsi, ApnConfig apn, Fteid enbS1u, Fteid pgwS5c) {
        Fteid s11c = teids.allocate(localS11, GtpcShapes.IF_S11_SGW);
        Fteid s1u = teids.allocate(localS1u, GtpcShapes.IF_S1U_SGW);
        Fteid s5u = teids.allocate(localS1u, GtpcShapes.IF_S5U_SGW);
        CreateSessionResult pgwResult = pgw.createSession(imsi, apn, s5u);
        return complete(imsi, apn, s11c, s1u, s5u, null, enbS1u, pgwResult);
    }

    /** The S11 transaction an SGW-C answers once its S5-C leg completes. */
    public record S11Context(InetSocketAddress mme, int sequence, byte recovery, Fteid mmeS11,
                             int ebi, byte[] bearerQos, List<Gtpv2Ie> passThrough) {
        public S11Context {
            Objects.requireNonNull(mme);
            passThrough = passThrough == null ? List.of() : List.copyOf(passThrough);
        }
    }

    /** An S11 Create Session outcome: accepted with its result, or rejected with a GTP cause. */
    public record CreateOutcome(S11Context s11, SgwCreateSessionResult result, int cause) {
        public boolean accepted() {
            return result != null;
        }
    }

    /** An S11 Delete Session outcome (always answered; cause 16 or the PGW-C's cause). */
    public record DeleteOutcome(S11Context s11, Fteid mmeS11, int cause) {}

    private record PendingCreate(S11Context s11, Imsi imsi, ApnConfig apn, Fteid s11c, Fteid s1u,
                                 Fteid s5c, Fteid s5u, int sequence) {}

    private record PendingDelete(S11Context s11, int s11cTeid, int sequence) {}

    /**
     * Per-session control-plane peers. Kept in the {@link SessionCheckpoint} (shared store), the
     * map is a node-local cache: after a failover another node rebuilds them from the store.
     */
    public record ControlPeers(Fteid mmeS11, Fteid sgwS5c, Fteid pgwS5c, Fteid pgwS5u,
                               InetSocketAddress pgwc) {}

    /**
     * S11 Create Session. In-process S5 → the outcome now. Wire S5-C → a Create Session Request to
     * the PGW-C (TS 29.274 §7.2.1) and an empty result: the S11 answer follows
     * {@link #onS5cCreateResponse} or {@link #onS5cTimeout}. Never blocks the SBB thread — the
     * S5-C response is itself a SLEE event that a blocked router could not deliver.
     */
    public java.util.Optional<CreateOutcome> beginCreateSession(
            Imsi imsi, ApnConfig apn, S11Context s11, Fteid pgwS5cFromMme) {
        if (!wire) {
            SgwCreateSessionResult r = createSession(imsi, apn, null, pgwS5cFromMme);
            rememberPeers(r.sgwS11c().teid(), new ControlPeers(s11.mmeS11(), null, r.pgw().pgwS5c(),
                    r.pgw().pgwS5u(), null));
            return java.util.Optional.of(new CreateOutcome(s11, r, GtpcShapes.CAUSE_ACCEPTED));
        }
        InetSocketAddress peer = resolvePgwcPeer(pgwS5cFromMme);
        if (gtp == null || peer == null) {
            throw new IllegalStateException(
                    "epc.s5c.wire=true but gtpv2c-ra / epc.s5c.pgwc.peer not configured");
        }
        Fteid s11c = teids.allocate(localS11, GtpcShapes.IF_S11_SGW);
        Fteid s1u = teids.allocate(localS1u, GtpcShapes.IF_S1U_SGW);
        Fteid s5c = teids.allocate(localS11, GtpcShapes.IF_S5C_SGW);
        Fteid s5u = teids.allocate(localS1u, GtpcShapes.IF_S5U_SGW);
        int seq = nextS5cSequence();
        pendingCreates.put(s5c.teid(), new PendingCreate(s11, imsi, apn, s11c, s1u, s5c, s5u, seq));
        pendingBySeq.put(seq, s5c.teid());
        gtp.sendCommand(new SendGtpv2Message(
                buildS5cCreateSessionRequest(imsi, apn, s5c, s5u, s11, seq), peer));
        return java.util.Optional.empty();
    }

    /** S5-C Create Session Response: correlated on the header TEID (= our S5-C TEID, §5.5.2). */
    public java.util.Optional<CreateOutcome> onS5cCreateResponse(Gtpv2Message rsp) {
        PendingCreate p = takePendingCreate(rsp);
        if (p == null) {
            LOG.debug("SgwControl unsolicited S5-C Create Session Response teid=0x{} seq={}",
                    Integer.toHexString(rsp.teid()), rsp.sequence());
            return java.util.Optional.empty();
        }
        int cause = GtpcShapes.cause(rsp);
        if (cause != GtpcShapes.CAUSE_ACCEPTED) {
            return java.util.Optional.of(new CreateOutcome(p.s11(), null, cause < 0
                    ? GtpcShapes.CAUSE_MANDATORY_IE_MISSING : cause));
        }
        S5cSessionResult s5;
        try {
            s5 = decodeS5cResponse(rsp);
        } catch (RuntimeException malformed) {
            LOG.warn("SgwControl S5-C Create Session Response unusable: {}", malformed.getMessage());
            return java.util.Optional.of(new CreateOutcome(p.s11(), null, GtpcShapes.CAUSE_MANDATORY_IE_MISSING));
        }
        CreateSessionResult pgwResult = new CreateSessionResult(s5.ue(), s5.pgwS5c(), s5.pgwS5u(),
                ChargingId.NONE, PolicyDecision.staticDefault(), 0L, s5.pco());
        SgwCreateSessionResult r = complete(p.imsi(), p.apn(), p.s11c(), p.s1u(), p.s5u(), p.s5c(), null, pgwResult);
        rememberPeers(p.s11c().teid(), new ControlPeers(p.s11().mmeS11(), p.s5c(), s5.pgwS5c(),
                s5.pgwS5u(), resolvePgwcPeer(s5.pgwS5c())));
        return java.util.Optional.of(new CreateOutcome(p.s11(), r, GtpcShapes.CAUSE_ACCEPTED));
    }

    /**
     * T3 × N3 expired on an S5-C request ({@code GtpProcedureTimeoutEvent}): the MME gets
     * "Remote peer not responding" (Create) or a local-cleanup accept (Delete).
     */
    public java.util.Optional<Object> onS5cTimeout(int sequence) {
        Integer key = pendingBySeq.remove(sequence);
        if (key == null) {
            return java.util.Optional.empty();
        }
        PendingCreate c = pendingCreates.remove(key);
        if (c != null) {
            return java.util.Optional.of(new CreateOutcome(c.s11(), null,
                    GtpcShapes.CAUSE_REMOTE_PEER_NOT_RESPONDING));
        }
        PendingDelete d = pendingDeletes.remove(key);
        if (d != null) {
            return java.util.Optional.of(finishDelete(d.s11(), d.s11cTeid(), GtpcShapes.CAUSE_ACCEPTED));
        }
        return java.util.Optional.empty();
    }

    /**
     * S11 Delete Session (§7.2.9): wire → Delete Session Request to the PGW-C on its S5-C TEID,
     * answer when it responds (or times out); in-process → now. The Sxa session goes either way.
     */
    public java.util.Optional<DeleteOutcome> beginDeleteSession(int s11cTeid, S11Context s11) {
        ControlPeers peers = controlPeers(s11cTeid);
        if (!wire || peers == null || peers.pgwS5c() == null || peers.sgwS5c() == null
                || peers.pgwc() == null || gtp == null) {
            sessions.findByS11cTeid(s11cTeid).ifPresent(cp ->
                    pgw.deleteSession(new Imsi(cp.imsiDigits()), cp.apn()));
            return java.util.Optional.of(finishDelete(s11, s11cTeid, GtpcShapes.CAUSE_ACCEPTED));
        }
        int seq = nextS5cSequence();
        pendingDeletes.put(peers.sgwS5c().teid(), new PendingDelete(s11, s11cTeid, seq));
        pendingBySeq.put(seq, peers.sgwS5c().teid());
        gtp.sendCommand(new SendGtpv2Message(new Gtpv2Message(Gtpv2MessageType.DELETE_SESSION_REQUEST,
                peers.pgwS5c().teid(), seq, (byte) 1,
                List.of(new Gtpv2Ie(Gtpv2Ie.EBI, 0, new byte[] {(byte) s11.ebi()}))), peers.pgwc()));
        return java.util.Optional.empty();
    }

    /** S5-C Delete Session Response, correlated on the header TEID (our S5-C TEID). */
    public java.util.Optional<DeleteOutcome> onS5cDeleteResponse(Gtpv2Message rsp) {
        PendingDelete d = pendingDeletes.remove(rsp.teid());
        if (d == null) {
            Integer key = pendingBySeq.get(rsp.sequence());
            d = key == null ? null : pendingDeletes.remove(key);
        }
        if (d == null) {
            return java.util.Optional.empty();
        }
        pendingBySeq.remove(d.sequence());
        int cause = GtpcShapes.cause(rsp);
        return java.util.Optional.of(finishDelete(d.s11(), d.s11cTeid(),
                cause == GtpcShapes.CAUSE_CONTEXT_NOT_FOUND ? GtpcShapes.CAUSE_ACCEPTED : cause));
    }

    /** MME S11 F-TEID of a session (response header TEID on S11, §5.5.2). */
    public Fteid mmeS11(int s11cTeid) {
        ControlPeers p = controlPeers(s11cTeid);
        return p == null ? null : p.mmeS11();
    }

    /** Local cache, else the shared session store (a session another node set up). */
    public ControlPeers controlPeers(int s11cTeid) {
        ControlPeers local = controlPeers.get(s11cTeid);
        if (local != null) {
            return local;
        }
        return sessions.findByS11cTeid(s11cTeid)
                .filter(cp -> cp.mmeS11Key() != null)
                .map(cp -> {
                    ControlPeers p = new ControlPeers(cp.mmeS11(), cp.sgwS5c(), cp.pgwS5c(), cp.pgwS5u(),
                            hostPort(cp.pgwcPeer()));
                    controlPeers.put(s11cTeid, p);
                    return p;
                })
                .orElse(null);
    }

    private DeleteOutcome finishDelete(S11Context s11, int s11cTeid, int cause) {
        ControlPeers peers = controlPeers(s11cTeid);
        controlPeers.remove(s11cTeid);
        sessions.findByS11cTeid(s11cTeid).ifPresent(cp -> {
            pfcp.sendCommand(new PfcpSessionDeletion(sgwu, cp.pfcpSeid(), List.of()));
            sessions.remove(cp.sessionId());
        });
        return new DeleteOutcome(s11, peers == null ? null : peers.mmeS11(), cause);
    }

    private PendingCreate takePendingCreate(Gtpv2Message rsp) {
        PendingCreate p = pendingCreates.remove(rsp.teid());
        if (p == null) {
            // A PGW-C that put TEID 0 in the header (pre-§5.5.2) still correlates on sequence.
            Integer key = pendingBySeq.get(rsp.sequence());
            p = key == null ? null : pendingCreates.remove(key);
        }
        if (p != null) {
            pendingBySeq.remove(p.sequence());
        }
        return p;
    }

    private int nextS5cSequence() {
        // Bottom half of the 24-bit space; gtpv2c-ra keeps the top half for its Echo.
        return s5cSequence.getAndIncrement() & 0x7f_ffff;
    }

    private void rememberPeers(int s11cTeid, ControlPeers peers) {
        controlPeers.put(s11cTeid, peers);
        sessions.findByS11cTeid(s11cTeid).ifPresent(cp -> sessions.put(cp.withControlPeers(
                peers.mmeS11(), peers.sgwS5c(), peers.pgwS5c(),
                peers.pgwc() == null ? null : peers.pgwc().getAddress().getHostAddress() + ":" + peers.pgwc().getPort(),
                0)));
    }

    private static InetSocketAddress hostPort(String hp) {
        if (hp == null || hp.isBlank()) {
            return null;
        }
        int c = hp.lastIndexOf(':');
        return new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1)));
    }

    /** Sxa program + SGW checkpoint once the PGW-C side is known. */
    private SgwCreateSessionResult complete(Imsi imsi, ApnConfig apn, Fteid s11c, Fteid s1u, Fteid s5u,
                                            Fteid s5c, Fteid enbS1u, CreateSessionResult pgwResult) {
        long se = seid.getAndIncrement();
        pfcp.sendCommand(new PfcpSessionEstablishment(sgwu, se,
                SxaRules.establish(s1u, s5u, pgwResult.pgwS5u(), enbS1u, pgwResult.ue().ipv4())));
        SessionKeys keys = new SessionKeys(imsi, apn.apn(), new BearerId(5));
        String ue = pgwResult.ue().ipv4() == null ? "" : pgwResult.ue().ipv4().getHostAddress();
        sessions.put(new SessionCheckpoint(
                SessionCheckpoint.sgwId(keys), "sgw", imsi.digits(), apn.apn(), 5,
                cluster.nodeId(), generation.getAndIncrement(), ue,
                s1u.key(), s5u.key(), pgwResult.pgwS5u().key(),
                pgwResult.chargingId().value(), Long.toUnsignedString(se),
                s11c.teid(), se));
        return new SgwCreateSessionResult(pgwResult.ue(), s11c, s1u, s5u, pgwResult);
    }


    /**
     * Resolve the S5-C peer: prefer the MME-provided PGW S5/S8 control F-TEID,
     * falling back to the configured {@code epc.s5c.pgwc.peer}. The GTP-C port is
     * inherited from the configured peer (default 2123).
     */
    private InetSocketAddress resolvePgwcPeer(Fteid pgwS5c) {
        if (pgwS5c != null && pgwS5c.address() != null) {
            int port = pgwcPeer != null ? pgwcPeer.getPort() : 2123;
            return new InetSocketAddress(pgwS5c.address(), port);
        }
        return pgwcPeer;
    }

    /**
     * S5-C Create Session Request (TS 29.274 Table 7.2.1-1, S5/S8): IMSI, the MME's pass-through
     * IEs, Sender F-TEID for CP (SGW S5-C, instance 0), APN, Bearer Context to be created
     * {EBI, S5/S8-U SGW F-TEID (instance 2), Bearer QoS}. Header TEID 0 (first message).
     */
    public static Gtpv2Message buildS5cCreateSessionRequest(
            Imsi imsi, ApnConfig apn, Fteid sgwS5c, Fteid sgwS5u, S11Context s11, int sequence) {
        List<Gtpv2Ie> ies = new java.util.ArrayList<>();
        ies.add(new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd(imsi.digits())));
        if (s11 != null) {
            ies.addAll(s11.passThrough());
        }
        ies.add(GtpcShapes.fteid(0, sgwS5c));
        ies.add(new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn(apn.apn())));
        List<Gtpv2Ie> bearer = new java.util.ArrayList<>();
        bearer.add(GtpcShapes.fteid(2, sgwS5u));
        if (s11 != null && s11.bearerQos() != null) {
            bearer.add(new Gtpv2Ie(GtpcShapes.BEARER_QOS, 0, s11.bearerQos()));
        }
        ies.add(GtpcShapes.bearerContext(s11 == null ? 5 : s11.ebi(), null, bearer));
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, sequence, (byte) 1, ies);
    }

    /**
     * S5-C Create Session Response: Sender F-TEID (PGW S5-C, instance 0), PAA, Bearer Context
     * created {EBI, Cause, S5/S8-U PGW F-TEID}, PCO relayed toward the MME.
     */
    public static S5cSessionResult decodeS5cResponse(Gtpv2Message response) {
        Fteid pgwS5c = GtpcShapes.fteidAt(response, 0);
        if (pgwS5c == null) {
            throw new IllegalArgumentException("CREATE_SESSION_RESPONSE missing the PGW S5-C F-TEID");
        }
        Fteid pgwS5u = GtpcShapes.bearerFteid(GtpcShapes.firstBearer(response), GtpcShapes.IF_S5U_PGW);
        if (pgwS5u == null) {
            throw new IllegalArgumentException("CREATE_SESSION_RESPONSE missing the S5/S8-U PGW F-TEID");
        }
        UeAddresses ue = decodePdnAddress(response);
        return new S5cSessionResult(ue, pgwS5c, pgwS5u, Gtpv2Ies.pcoFrom(response));
    }

    public Fteid modifyAccess(Imsi imsi, String apn, Fteid newEnbS1u) {
        SessionCheckpoint cp = sessions.get(SessionCheckpoint.sgwId(
                new SessionKeys(imsi, apn, new BearerId(5)))).orElseThrow();
        return modifyAccess(cp, newEnbS1u);
    }

    /** GTP-C correlates Modify Bearer on header TEID, not IMSI. */
    public Fteid modifyAccessByTeid(int s11cTeid, Fteid newEnbS1u) {
        SessionCheckpoint cp = sessions.findByS11cTeid(s11cTeid).orElseThrow();
        return modifyAccess(cp, newEnbS1u);
    }

    /**
     * Modify Bearer (TS 23.401 §5.3.4.1 step 13 / TS 29.244 §7.5.4): Session Modification on the
     * existing Sxa session — Update FAR of the downlink to FORW toward the eNB S1-U F-TEID.
     */
    private Fteid modifyAccess(SessionCheckpoint cp, Fteid newEnbS1u) {
        pfcp.sendCommand(new PfcpSessionModification(sgwu, cp.pfcpSeid(), SxaRules.downlinkTo(newEnbS1u)));
        return cp.sgwS1u();
    }

    /**
     * Release Access Bearers: clear S1-U forwarding, keep the S5 checkpoint.
     * @return true if the S11 TEID was known
     */
    public boolean releaseAccessByTeid(int s11cTeid) {
        return sessions.findByS11cTeid(s11cTeid).map(cp -> {
            // TS 23.401 §5.3.5 S1 release: the downlink FAR buffers and notifies the CP
            // (Downlink Data Notification); the uplink and the S5 leg stay.
            pfcp.sendCommand(new PfcpSessionModification(sgwu, cp.pfcpSeid(), SxaRules.downlinkBuffered()));
            return true;
        }).orElse(false);
    }

    /**
     * Boot-time peer probes: PFCP association to the SGW-U, and (in wire mode) a
     * GTP Echo to the S5-C PGW-C peer so the link status board reflects honest
     * peer evidence instead of just a bound socket.
     */
    public void probePeers() {
        if (pfcp != null && sgwu != null) {
            pfcp.sendCommand(new PfcpAssociateCommand(sgwu));
        }
        if (gtp != null && wire && pgwcPeer != null) {
            gtp.sendCommand(new GtpEchoCommand(pgwcPeer));
        }
    }

    private static UeAddresses decodePdnAddress(Gtpv2Message msg) {
        Gtpv2Ie ie = msg.first(Gtpv2Ie.PDN_ADDRESS);
        if (ie == null || ie.value().length < 5 || (ie.value()[0] & 0x07) != 1) {
            return UeAddresses.none();
        }
        try {
            InetAddress a = InetAddress.getByAddress(Arrays.copyOfRange(ie.value(), 1, 5));
            if (a instanceof Inet4Address v4) {
                return new UeAddresses(v4, null);
            }
        } catch (Exception ignored) {
        }
        return UeAddresses.none();
    }

    private static PfcpFteid pf(Fteid f) {
        return new PfcpFteid(f.teid(), f.address(), f.interfaceType());
    }

    private static GtpFteid gtpFteid(Fteid f) {
        return new GtpFteid(f.teid(), f.address(), f.interfaceType());
    }

    private static Fteid apiFteid(GtpFteid g) {
        return new Fteid(g.teid(), g.address(), g.interfaceType());
    }
}
