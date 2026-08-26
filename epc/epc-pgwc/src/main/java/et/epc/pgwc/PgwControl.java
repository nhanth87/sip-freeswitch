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
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.PfcpUsageReport;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
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

    public CreateSessionResult createSession(Imsi imsi, ApnConfig apn, Fteid sgwS5u) {
        UeAddresses ue = ipam.allocate(imsi, apn.pdnType());
        ChargingId charging = null;
        long se = seid.getAndIncrement();
        try {
            charging = pcef.open(imsi, apn.apn(), PcefService.Product.POSTPAID);
            String ueIp = ue.ipv4() == null ? "" : ue.ipv4().getHostAddress();
            // Gx seam: wire CCR-I to the PCRF peer when pcrfLive (static
            // decision otherwise) — PcefService.beginGxSession does the gate.
            PolicyDecision policy = pcef.beginGxSession(imsi, apn.apn(), ueIp,
                    charging.value(), WirePcrfClient.REQ_TYPE_INITIAL);
            Fteid s5c = teids.allocate(localS5, 6);
            Fteid s5u = teids.allocate(localS5, 5);
            pfcp.sendCommand(new PfcpProgramSession(pgwu, se, List.of(
                    new PfcpRule(se, "pdr-ul", pf(s5u), pf(sgwS5u), "qer-1", true),
                    new PfcpRule(se, "pdr-dl", pf(sgwS5u), pf(s5u), "qer-1", false))));
            SessionKeys keys = new SessionKeys(imsi, apn.apn(), new BearerId(5));
            sessions.put(new SessionCheckpoint(
                    SessionCheckpoint.pgwId(keys), "pgw", imsi.digits(), apn.apn(), 5,
                    cluster.nodeId(), generation.getAndIncrement(), ueIp,
                    null, sgwS5u.key(), s5u.key(), charging.value(),
                    Long.toUnsignedString(se), 0, se));
            bySeid.put(se, SessionCheckpoint.pgwId(keys));
            return new CreateSessionResult(ue, s5c, s5u, charging, policy, se,
                    apn.pcoContainers());
        } catch (RuntimeException e) {
            ipam.release(imsi);
            bySeid.remove(se);
            if (charging != null) {
                pcef.close(charging);
            }
            throw e;
        }
    }

    public void deleteSession(Imsi imsi, String apn) {
        SessionKeys keys = new SessionKeys(imsi, apn, new BearerId(5));
        sessions.get(SessionCheckpoint.pgwId(keys)).ifPresent(cp -> {
            sessions.remove(cp.sessionId());
            bySeid.remove(cp.pfcpSeid());
            ipam.release(imsi);
            pcef.close(new ChargingId(cp.chargingId()));
        });
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
