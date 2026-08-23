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
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * SGW-C: S11 procedures, mobility anchor, Sxa PFCP. Default bearer is ack'd
 * only after PGW-C IP allocation and both UP rule sets are programmed.
 *
 * <p>S5-C can run either in-process (lab fallback, {@code epc.s5c.wire=false})
 * or on the wire (split SGW-C/PGW-C, {@code epc.s5c.wire=true}). In wire mode
 * {@link #createSession} sends {@code CREATE_SESSION_REQUEST} through
 * {@code gtpv2c-ra} to the configured PGW-C peer and correlates the
 * {@code CREATE_SESSION_RESPONSE} by GTPv2 sequence number via
 * {@link #onS5cResponse}.
 */
public final class SgwControl {

    private static final Logger LOG = LogManager.getLogger(SgwControl.class);
    public static final long S5C_TIMEOUT_MILLIS = 5_000L;

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
    private final ConcurrentHashMap<Integer, CompletableFuture<S5cSessionResult>> s5cPending =
            new ConcurrentHashMap<>();

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
     * {@code pgwS5c} is the MME-provided PGW S5/S8 control-plane F-TEID
     * (interface 5..8). When non-null and {@code epc.s5c.wire=true} it overrides
     * the configured {@code epc.s5c.pgwc.peer} so the S5-C request follows the
     * PGW-C the MME selected instead of a hard-coded default.
     */
    public SgwCreateSessionResult createSession(
            Imsi imsi, ApnConfig apn, Fteid enbS1u, Fteid pgwS5c) {
        Fteid s11c = teids.allocate(localS11, 10);
        Fteid s1u = teids.allocate(localS1u, 0);
        Fteid s5u = teids.allocate(localS11, 4);
        CreateSessionResult pgwResult = wire
                ? createSessionWire(imsi, apn, s5u, pgwS5c)
                : pgw.createSession(imsi, apn, s5u);
        long se = seid.getAndIncrement();
        pfcp.sendCommand(new PfcpProgramSession(sgwu, se, List.of(
                new PfcpRule(se, "pdr-ul", pf(s1u), pf(enbS1u), "qer-1", true),
                new PfcpRule(se, "pdr-dl", pf(enbS1u), pf(s1u), "qer-1", false))));
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

    /** S5-C split path: send CREATE_SESSION_REQUEST and block for the correlated response. */
    private CreateSessionResult createSessionWire(
            Imsi imsi, ApnConfig apn, Fteid sgwS5u, Fteid pgwS5c) {
        InetSocketAddress peer = resolvePgwcPeer(pgwS5c);
        if (gtp == null || peer == null) {
            throw new IllegalStateException(
                    "epc.s5c.wire=true but gtpv2c-ra / epc.s5c.pgwc.peer not configured");
        }
        int seq = s5cSequence.getAndIncrement() & 0xff_ffff;
        Gtpv2Message req = buildS5cCreateSessionRequest(imsi, apn, sgwS5u, seq);
        CompletableFuture<S5cSessionResult> pending = new CompletableFuture<>();
        s5cPending.put(seq, pending);
        gtp.sendCommand(new SendGtpv2Message(req, peer));
        try {
            S5cSessionResult r = pending.get(S5C_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return new CreateSessionResult(r.ue(), r.pgwS5c(), r.pgwS5u(),
                    ChargingId.NONE, PolicyDecision.staticDefault(), 0L);
        } catch (Exception e) {
            throw new IllegalStateException("S5-C create session failed seq=" + seq, e);
        } finally {
            s5cPending.remove(seq);
        }
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

    /** Complete a pending S5-C request; called by the GTP SBB on CREATE_SESSION_RESPONSE. */
    public void onS5cResponse(Gtpv2Message response) {
        CompletableFuture<S5cSessionResult> pending = s5cPending.remove(response.sequence());
        if (pending == null) {
            LOG.debug("SgwControl unknown/unsolicited S5-C response seq={}", response.sequence());
            return;
        }
        try {
            pending.complete(decodeS5cResponse(response));
        } catch (RuntimeException e) {
            pending.completeExceptionally(e);
        }
    }

    /** Build a split S5-C CREATE_SESSION_REQUEST (IMSI + APN + SGW S5-U F-TEID). */
    public static Gtpv2Message buildS5cCreateSessionRequest(
            Imsi imsi, ApnConfig apn, Fteid sgwS5u, int sequence) {
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_REQUEST, 0, sequence, (byte) 1,
                List.of(
                        new Gtpv2Ie(Gtpv2Ie.IMSI, 0, Gtpv2Ies.tbcd(imsi.digits())),
                        new Gtpv2Ie(Gtpv2Ie.APN, 0, Gtpv2Ies.encodeApn(apn.apn())),
                        new Gtpv2Ie(Gtpv2Ie.FTEID, 0, Gtpv2Ies.encodeFteid(gtpFteid(sgwS5u)))));
    }

    /** Decode a split S5-C CREATE_SESSION_RESPONSE into the SGW-relevant fields. */
    public static S5cSessionResult decodeS5cResponse(Gtpv2Message response) {
        Gtpv2Ie fteid = response.first(Gtpv2Ie.FTEID);
        if (fteid == null) {
            throw new IllegalArgumentException("CREATE_SESSION_RESPONSE missing S5-C F-TEID");
        }
        Fteid pgwS5c = apiFteid(Gtpv2Ies.decodeFteid(fteid.value()));
        Fteid pgwS5u = apiFteid(Gtpv2Ies.s1uFromBearerContext(response));
        UeAddresses ue = decodePdnAddress(response);
        return new S5cSessionResult(ue, pgwS5c, pgwS5u);
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

    private Fteid modifyAccess(SessionCheckpoint cp, Fteid newEnbS1u) {
        Fteid s1u = cp.sgwS1u();
        long se = cp.pfcpSeid();
        pfcp.sendCommand(new PfcpProgramSession(sgwu, se, List.of(
                new PfcpRule(se, "pdr-ul", pf(s1u), pf(newEnbS1u), "qer-1", true),
                new PfcpRule(se, "pdr-dl", pf(newEnbS1u), pf(s1u), "qer-1", false))));
        return s1u;
    }

    /**
     * Release Access Bearers: clear S1-U forwarding, keep the S5 checkpoint.
     * @return true if the S11 TEID was known
     */
    public boolean releaseAccessByTeid(int s11cTeid) {
        return sessions.findByS11cTeid(s11cTeid).map(cp -> {
            long se = cp.pfcpSeid();
            // This is a Deactivate-DL FAR: drop the S1-U downlink PDR while keeping
            // the S5 / UP session alive. ra-pfcp has no discrete FAR modify type
            // yet (task_0001), so represent the deactivation as a session reprogram
            // that retains the uplink S1-U -> S5-U leg and removes the downlink PDR.
            pfcp.sendCommand(new PfcpProgramSession(sgwu, se, List.of(
                    new PfcpRule(se, "pdr-ul", pf(cp.sgwS1u()), pf(cp.sgwS5u()), "qer-1", true))));
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
