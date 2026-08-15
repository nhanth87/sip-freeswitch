package et.epc.sgwc;

import et.epc.api.ApnConfig;
import et.epc.api.BearerId;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.SessionKeys;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.SessionCheckpoint;
import et.epc.api.cluster.SessionStore;
import et.epc.api.cluster.TeidAllocator;
import et.epc.pgwc.CreateSessionResult;
import et.epc.pgwc.PgwControl;
import com.microjainslee.ra.pfcp.PfcpFteid;
import com.microjainslee.ra.pfcp.PfcpRule;
import com.microjainslee.ra.pfcp.command.PfcpProgramSession;
import com.microjainslee.api.RaCommandPort;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SGW-C: S11 procedures, mobility anchor, Sxa PFCP. Default bearer is ack'd
 * only after PGW-C IP allocation and both UP rule sets are programmed.
 */
public final class SgwControl {

    private final EpcClusterConfig cluster;
    private final TeidAllocator teids;
    private final SessionStore sessions;
    private final PgwControl pgw;
    private final RaCommandPort pfcp;
    private final InetAddress localS11;
    private final InetAddress localS1u;
    private final InetSocketAddress sgwu;
    private final AtomicLong seid;
    private final AtomicLong generation = new AtomicLong(1);

    public SgwControl(
            EpcClusterConfig cluster,
            SessionStore sessions,
            PgwControl pgw,
            RaCommandPort pfcp,
            InetAddress localS11,
            InetAddress localS1u,
            InetSocketAddress sgwu) {
        this.cluster = Objects.requireNonNull(cluster);
        this.teids = new TeidAllocator(cluster.nodeIndex());
        this.sessions = Objects.requireNonNull(sessions);
        this.pgw = Objects.requireNonNull(pgw);
        this.pfcp = Objects.requireNonNull(pfcp);
        this.localS11 = Objects.requireNonNull(localS11);
        this.localS1u = Objects.requireNonNull(localS1u);
        this.sgwu = Objects.requireNonNull(sgwu);
        this.seid = new AtomicLong((((long) cluster.nodeIndex()) << 32) | 1_000L);
    }

    public SgwCreateSessionResult createSession(Imsi imsi, ApnConfig apn, Fteid enbS1u) {
        Fteid s11c = teids.allocate(localS11, 10);
        Fteid s1u = teids.allocate(localS1u, 0);
        Fteid s5u = teids.allocate(localS11, 4);
        CreateSessionResult pgwResult = pgw.createSession(imsi, apn, s5u);
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
            pfcp.sendCommand(new PfcpProgramSession(sgwu, cp.pfcpSeid(), List.of()));
            return true;
        }).orElse(false);
    }

    private static PfcpFteid pf(Fteid f) {
        return new PfcpFteid(f.teid(), f.address(), f.interfaceType());
    }
}
