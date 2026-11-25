package et.epc.pgwc;

import com.microjainslee.api.RaCommandPort;
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
import et.epc.pcef.PcefService;
import et.epc.pcef.PolicyDecision;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpSessionDeletion;
import com.microjainslee.ra.pfcp.command.PfcpSessionEstablishment;
import et.epc.pcef.UsageRecord;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PGW-C domain helper invoked by {@code GtpGatewaySbb}. PFCP leaves on
 * {@code pfcp-ra} via {@link RaCommandPort}; this class does not bind sockets.
 */
public final class PgwControl {

    private static final Logger LOG = LogManager.getLogger(PgwControl.class);

    private final EpcClusterConfig cluster;
    private final TeidAllocator teids;
    private final Ipam ipam;
    private final PcefService pcef;
    private final SessionStore sessions;
    private final RaCommandPort pfcp;
    private final InetAddress localS5;
    private final InetSocketAddress pgwu;
    private final AtomicLong seid;
    private final AtomicLong generation = new AtomicLong(1);
    /** Sxb SEID -> PGW checkpoint id correlate for inbound PFCP Session Reports. */
    private final ConcurrentHashMap<Long, String> bySeid = new ConcurrentHashMap<>();
    /** PGW S5-C TEID → SGW-C peer: a cache of the PGW checkpoint (shared store, survives failover). */
    private final ConcurrentHashMap<Integer, S5cPeer> s5cPeers = new ConcurrentHashMap<>();

    public PgwControl(
            EpcClusterConfig cluster,
            Ipam ipam,
            PcefService pcef,
            SessionStore sessions,
            RaCommandPort pfcp,
            InetAddress localS5,
            InetSocketAddress pgwu) {
        this.cluster = Objects.requireNonNull(cluster);
        this.teids = new TeidAllocator(cluster.nodeIndex());
        this.ipam = Objects.requireNonNull(ipam);
        this.pcef = Objects.requireNonNull(pcef);
        this.sessions = Objects.requireNonNull(sessions);
        this.pfcp = Objects.requireNonNull(pfcp);
        this.localS5 = Objects.requireNonNull(localS5);
        this.pgwu = Objects.requireNonNull(pgwu);
        this.seid = new AtomicLong((((long) cluster.nodeIndex()) << 32) | 1L);
    }

    /** Boot-time Sxb PFCP association probe to the PGW-U. */
    public void probePeers() {
        if (pfcp != null && pgwu != null) {
            pfcp.sendCommand(new PfcpAssociateCommand(pgwu));
        }
    }

    /** Resources of one PDN connection reserved before the policy is known (TS 23.401 §5.3.2.1 step 14). */
    public record Allocation(Imsi imsi, ApnConfig apn, UeAddresses ue, ChargingId charging,
                             Fteid pgwS5c, Fteid pgwS5u, long seid) {
        public String ueIpv4() {
            return ue.ipv4() == null ? "" : ue.ipv4().getHostAddress();
        }
    }

    /** UE IP, Charging-Id, S5-C / S5-U TEIDs and the Sxb SEID; nothing is programmed yet. */
    public Allocation allocate(Imsi imsi, ApnConfig apn) {
        UeAddresses ue = ipam.allocate(imsi, apn.pdnType());
        try {
            ChargingId charging = pcef.open(imsi, apn.apn(), PcefService.Product.POSTPAID);
            // TS 29.274 §8.22: 7 = S5/S8 PGW GTP-C, 5 = S5/S8 PGW GTP-U.
            return new Allocation(imsi, apn, ue, charging, teids.allocate(localS5, 7),
                    teids.allocate(localS5, 5), seid.getAndIncrement());
        } catch (RuntimeException e) {
            ipam.release(imsi);
            throw e;
        }
    }

    /** Give back what {@link #allocate} reserved (policy rejected the session). */
    public void release(Allocation a) {
        ipam.release(a.imsi());
        pcef.close(a.charging());
    }

    /** Sxb Session Establishment (anchor rules) + PGW checkpoint, once the policy is decided. */
    public CreateSessionResult complete(Allocation a, Fteid sgwS5u, PolicyDecision policy) {
        try {
            pfcp.sendCommand(new PfcpSessionEstablishment(pgwu, a.seid(),
                    SxbRules.establish(pf(a.pgwS5u()), pf(sgwS5u), a.ue().ipv4())));
            SessionKeys keys = new SessionKeys(a.imsi(), a.apn().apn(), new BearerId(5));
            sessions.put(new SessionCheckpoint(
                    SessionCheckpoint.pgwId(keys), "pgw", a.imsi().digits(), a.apn().apn(), 5,
                    cluster.nodeId(), generation.getAndIncrement(), a.ueIpv4(),
                    null, sgwS5u.key(), a.pgwS5u().key(), a.charging().value(),
                    Long.toUnsignedString(a.seid()), 0, a.seid()));
            bySeid.put(a.seid(), SessionCheckpoint.pgwId(keys));
            return new CreateSessionResult(a.ue(), a.pgwS5c(), a.pgwS5u(), a.charging(), policy, a.seid(),
                    a.apn().pcoContainers());
        } catch (RuntimeException e) {
            bySeid.remove(a.seid());
            release(a);
            throw e;
        }
    }

    /**
     * One step: allocate, take the policy {@link PcefService#beginGxSession} gives synchronously
     * (in-process PCRF or the static decision — never a wire CCR wait), program Sxb. The wire Gx
     * path is two-phase in {@code PgwS5cProcedure}: {@link #allocate} → CCA event → {@link #complete}.
     */
    public CreateSessionResult createSession(Imsi imsi, ApnConfig apn, Fteid sgwS5u) {
        Allocation a = allocate(imsi, apn);
        PolicyDecision policy;
        try {
            policy = pcef.beginGxSession(imsi, apn.apn(), a.ueIpv4(), a.charging().value(),
                    WirePcrfClient.REQ_TYPE_INITIAL);
        } catch (RuntimeException e) {
            release(a);
            throw e;
        }
        return complete(a, sgwS5u, policy);
    }

    /** The Charging-Id of a PGW session (the Gx session is keyed on it), or 0. */
    public long chargingId(Imsi imsi, String apn) {
        return sessions.get(SessionCheckpoint.pgwId(new SessionKeys(imsi, apn, new BearerId(5))))
                .map(SessionCheckpoint::chargingId).orElse(0L);
    }

    public void deleteSession(Imsi imsi, String apn) {
        SessionKeys keys = new SessionKeys(imsi, apn, new BearerId(5));
        sessions.get(SessionCheckpoint.pgwId(keys)).ifPresent(cp -> {
            sessions.remove(cp.sessionId());
            bySeid.remove(cp.pfcpSeid());
            pfcp.sendCommand(new PfcpSessionDeletion(pgwu, cp.pfcpSeid(), List.of()));
            ipam.release(imsi);
            pcef.close(new ChargingId(cp.chargingId()));
        });
    }

    /** The SGW-C end of an S5-C session: who to answer, and with which header TEID. */
    public record S5cPeer(Imsi imsi, String apn, Fteid sgwS5c) {}

    /** Remember the SGW-C's S5-C F-TEID under our PGW S5-C TEID (header TEID of its later requests). */
    public void registerS5c(int pgwS5cTeid, S5cPeer peer) {
        s5cPeers.put(pgwS5cTeid, peer);
        String id = SessionCheckpoint.pgwId(new SessionKeys(peer.imsi(), peer.apn(), new BearerId(5)));
        sessions.get(id).ifPresent(cp -> sessions.put(
                cp.withControlPeers(null, peer.sgwS5c(), null, null, pgwS5cTeid)));
    }

    /** The SGW-C end of an S5-C session by our S5-C TEID: local cache, else the shared store. */
    public S5cPeer s5cPeer(int pgwS5cTeid) {
        S5cPeer local = s5cPeers.get(pgwS5cTeid);
        if (local != null) {
            return local;
        }
        return sessions.findByS5cTeid(pgwS5cTeid)
                .filter(cp -> cp.sgwS5cKey() != null)
                .map(cp -> new S5cPeer(cp.imsi(), cp.apn(), cp.sgwS5c()))
                .orElse(null);
    }

    /** S5-C Delete Session (TS 29.274 §7.2.9): correlated on our S5-C TEID; null if unknown. */
    public S5cPeer deleteByS5cTeid(int pgwS5cTeid) {
        S5cPeer peer = s5cPeer(pgwS5cTeid);
        s5cPeers.remove(pgwS5cTeid);
        if (peer != null) {
            deleteSession(peer.imsi(), peer.apn());
        }
        return peer;
    }

    /**
     * Applies PGW-U Usage Reports from an inbound PFCP Session Report Request to
     * the PCEF charging session correlated by Sxb SEID, then checks the Gy
     * overrun guard (log-only; no OCS wire here).
     *
     * @return false when the SEID has no known PGW session (caller answers
     *         Cause "Session context not found"); never throws.
     */
    public boolean reportUsage(long seid, List<PfcpUsageReport> reports) {
        String sessionId = bySeid.get(seid);
        Optional<SessionCheckpoint> found =
                sessionId == null ? Optional.empty() : sessions.get(sessionId);
        if (found.isEmpty()) {
            bySeid.remove(seid);
            return false;
        }
        SessionCheckpoint cp = found.get();
        ChargingId charging = new ChargingId(cp.chargingId());
        for (PfcpUsageReport report : reports == null ? List.<PfcpUsageReport>of() : reports) {
            try {
                pcef.reportUsage(new UsageRecord(cp.imsi(), cp.apn(), charging,
                        report.uplinkVolume(), report.downlinkVolume()));
            } catch (RuntimeException e) {
                LOG.warn("PgwControl usage report dropped seid={} charging={} urr={}: {}",
                        seid, cp.chargingId(), report.urrId(), e.toString());
                return false;
            }
        }
        Long granted = pcef.grantedOctets(charging);
        if (pcef.isOverGrantedQuota(charging, granted == null ? 0L : granted)) {
            LOG.debug("PgwControl over granted Gy quota seid={} charging={} granted={}",
                    seid, cp.chargingId(), granted);
        }
        return true;
    }

    private static PfcpFteid pf(Fteid f) {
        return new PfcpFteid(f.teid(), f.address(), f.interfaceType());
    }
}
