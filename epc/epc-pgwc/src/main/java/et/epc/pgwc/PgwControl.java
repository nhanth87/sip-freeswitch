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
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PGW-C domain helper invoked by {@code GtpGatewaySbb}. PFCP leaves on
 * {@code pfcp-ra} via {@link RaCommandPort}; this class does not bind sockets.
 */
public final class PgwControl {

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

    public CreateSessionResult createSession(Imsi imsi, ApnConfig apn, Fteid sgwS5u) {
        UeAddresses ue = ipam.allocate(imsi, apn.pdnType());
        ChargingId charging = null;
        long se = seid.getAndIncrement();
        try {
            charging = pcef.open(imsi, apn.apn(), PcefService.Product.POSTPAID);
            PolicyDecision policy = pcef.authorize(apn.apn());
            Fteid s5c = teids.allocate(localS5, 6);
            Fteid s5u = teids.allocate(localS5, 5);
            pfcp.sendCommand(new PfcpProgramSession(pgwu, se, List.of(
                    new PfcpRule(se, "pdr-ul", pf(s5u), pf(sgwS5u), "qer-1", true),
                    new PfcpRule(se, "pdr-dl", pf(sgwS5u), pf(s5u), "qer-1", false))));
            SessionKeys keys = new SessionKeys(imsi, apn.apn(), new BearerId(5));
            String ueIp = ue.ipv4() == null ? "" : ue.ipv4().getHostAddress();
            sessions.put(new SessionCheckpoint(
                    SessionCheckpoint.pgwId(keys), "pgw", imsi.digits(), apn.apn(), 5,
                    cluster.nodeId(), generation.getAndIncrement(), ueIp,
                    null, sgwS5u.key(), s5u.key(), charging.value(),
                    Long.toUnsignedString(se), 0, se));
            return new CreateSessionResult(ue, s5c, s5u, charging, policy, se);
        } catch (RuntimeException e) {
            ipam.release(imsi);
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
            ipam.release(imsi);
            pcef.close(new ChargingId(cp.chargingId()));
        });
    }

    private static PfcpFteid pf(Fteid f) {
        return new PfcpFteid(f.teid(), f.address(), f.interfaceType());
    }
}
