package et.epc.host.sbb;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterTimeoutEvent;
import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;
import com.microjainslee.ra.gtpv2c.Gtpv2Message;
import com.microjainslee.ra.gtpv2c.Gtpv2MessageType;
import com.microjainslee.ra.gtpv2c.command.SendGtpv2Message;
import et.epc.api.ApnConfig;
import et.epc.api.Fteid;
import et.epc.api.Imsi;
import et.epc.api.QosProfile;
import et.epc.api.diameter.GxAvp;
import et.epc.pcef.PolicyDecision;
import et.epc.pgwc.CreateSessionResult;
import et.epc.pgwc.PgwControl;
import et.epc.pgwc.WirePcrfClient;
import et.epc.sgwc.GtpcShapes;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PGW-C side of S5-C Create Session with the Gx IP-CAN session establishment in between
 * (TS 23.401 §5.3.2.1 steps 13-15, TS 29.212 §4.5.1), as a two-phase micro-jainslee
 * procedure:
 *
 * <pre>
 *  S5-C Create Session Request  (GtpGatewaySbb)      → allocate UE IP / TEIDs / Charging-Id,
 *                                                       CCR-I, return
 *  Gx CCA                        (DiameterGatewaySbb) → Sxb Establishment, S5-C Response (PCRF QoS)
 *  Tx timeout                    (DiameterGatewaySbb) → same with the local static policy
 *  CCA Result-Code ≠ 2001                              → release, S5-C Response cause 73
 * </pre>
 *
 * No step blocks a SLEE thread. Without a wire PCRF (or with the peer not CEA-ready) the
 * session completes at once with the synchronous policy. The pending map is in-flight state of
 * this node only: if the node dies mid-procedure the SGW-C's T3/N3 retransmission reaches
 * whichever node owns the service address next.
 */
public final class PgwS5cProcedure {

    private static final Logger LOG = LogManager.getLogger(PgwS5cProcedure.class);

    /** TS 29.274 §8.4 cause 73 "No resources available" for a PCRF rejection. */
    static final int CAUSE_PCRF_REJECTED = 73;

    private record Pending(PgwControl.Allocation alloc, Imsi imsi, ApnConfig apn, Gtpv2Message req,
                           InetSocketAddress peer, Fteid sgwS5c, Fteid sgwS5u, int ebi) {}

    private final PgwControl pgw;
    private final java.util.function.Supplier<WirePcrfClient> gxClient;
    private final RaCommandPort gtp;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public PgwS5cProcedure(PgwControl pgw, WirePcrfClient gx, RaCommandPort gtp) {
        this(pgw, () -> gx, gtp);
    }

    /** The Gx client is wired after the SBBs (Diameter RA bootstrap); read it per procedure. */
    public PgwS5cProcedure(PgwControl pgw, java.util.function.Supplier<WirePcrfClient> gx, RaCommandPort gtp) {
        this.pgw = Objects.requireNonNull(pgw);
        this.gxClient = Objects.requireNonNull(gx);
        this.gtp = Objects.requireNonNull(gtp);
    }

    /** Phase 1, on the S5-C Create Session Request event. */
    public void begin(Gtpv2Message req, InetSocketAddress peer, Imsi imsi, ApnConfig apn,
                      Fteid sgwS5c, Fteid sgwS5u) {
        int ebi = GtpcShapes.ebi(req, 5);
        WirePcrfClient gx = gxClient.get();
        if (gx == null || !gx.canSend()) {
            CreateSessionResult r = pgw.createSession(imsi, apn, sgwS5u);
            answer(new Pending(null, imsi, apn, req, peer, sgwS5c, sgwS5u, ebi), r);
            return;
        }
        PgwControl.Allocation a = pgw.allocate(imsi, apn);
        String sid = gx.sessionIdFor(a.charging().value());
        pending.put(sid, new Pending(a, imsi, apn, req, peer, sgwS5c, sgwS5u, ebi));
        if (gx.sendCcr(imsi, apn.apn(), a.ueIpv4(), a.charging().value(), GxAvp.REQUEST_INITIAL, 0).isEmpty()) {
            Pending p = pending.remove(sid);
            if (p != null) {
                finish(p, policyFallback(p.apn().apn()));
            }
        }
    }

    /** Phase 2, on the Gx CCA event. @return false when the answer is not one of ours. */
    public boolean onCca(DiameterAnswerEvent cca) {
        if (!WirePcrfClient.isCca(cca)) {
            return false;
        }
        Pending p = pending.remove(cca.sessionId());
        if (p == null) {
            return false;
        }
        PolicyDecision policy = gxClient.get().decision(p.apn().apn(), cca);
        if (policy == null) {
            LOG.warn("Gx CCA rc={} sid={} — S5-C Create Session rejected", cca.resultCode(), cca.sessionId());
            pgw.release(p.alloc());
            send(reject(p), p.peer());
            return true;
        }
        LOG.info("Gx CCA sid={} qci={} apn-ambr={}/{} rules={}", cca.sessionId(), policy.qos().qci(),
                policy.qos().apnAmbrUlBps(), policy.qos().apnAmbrDlBps(), policy.rules().size());
        finish(p, policy);
        return true;
    }

    /** Phase 2 when the PCRF stays silent past the Tx timer: local policy (fail-open). */
    public boolean onTimeout(DiameterTimeoutEvent t) {
        if (t.applicationId() != GxAvp.APP_ID || t.commandCode() != GxAvp.CC_CMD) {
            return false;
        }
        Pending p = pending.remove(t.sessionId());
        if (p == null) {
            return false;
        }
        LOG.warn("Gx CCA timeout sid={} — local policy", t.sessionId());
        finish(p, policyFallback(p.apn().apn()));
        return true;
    }

    /** CCR-T for a PDN connection that went (fire-and-forget; the CCA is only logged). */
    public void terminate(Imsi imsi, String apn, long chargingId) {
        WirePcrfClient gx = gxClient.get();
        if (gx != null && chargingId > 0) {
            gx.sendCcr(imsi, apn, null, chargingId, GxAvp.REQUEST_TERMINATION, 1);
        }
    }

    public int pendingCount() {
        return pending.size();
    }

    private void finish(Pending p, PolicyDecision policy) {
        CreateSessionResult r;
        try {
            r = pgw.complete(p.alloc(), p.sgwS5u(), policy);
        } catch (RuntimeException e) {
            LOG.warn("PGW-C complete failed: {}", e.toString());
            send(reject(p), p.peer());
            return;
        }
        answer(p, r);
    }

    private void answer(Pending p, CreateSessionResult r) {
        pgw.registerS5c(r.pgwS5c().teid(), new PgwControl.S5cPeer(p.imsi(), p.apn().apn(), p.sgwS5c()));
        send(response(p.req(), p.sgwS5c(), p.ebi(), r), p.peer());
    }

    private PolicyDecision policyFallback(String apn) {
        return "ims".equalsIgnoreCase(apn) ? PolicyDecision.ims() : PolicyDecision.staticDefault();
    }

    private void send(Gtpv2Message msg, InetSocketAddress peer) {
        gtp.sendCommand(new SendGtpv2Message(msg, peer));
    }

    private static Gtpv2Message reject(Pending p) {
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE, p.sgwS5c().teid(), p.req().sequence(),
                p.req().recovery(), List.of(Gtpv2Ies.cause(CAUSE_PCRF_REJECTED)));
    }

    /**
     * S5-C Create Session Response (TS 29.274 Table 7.2.2-1, S5/S8): Cause, Sender F-TEID (PGW
     * S5-C), PAA, APN-AMBR, Bearer Context {EBI, Cause, S5/S8-U PGW F-TEID, Bearer QoS from the
     * policy, Charging-Id}, PCO. Header TEID = the SGW-C's S5-C TEID (§5.5.2).
     */
    static Gtpv2Message response(Gtpv2Message req, Fteid sgwS5c, int ebi, CreateSessionResult r) {
        List<Gtpv2Ie> ies = new ArrayList<>();
        ies.add(Gtpv2Ies.causeAccepted());
        ies.add(GtpcShapes.fteid(0, r.pgwS5c()));
        if (r.ue().ipv4() != null) {
            ies.add(new Gtpv2Ie(Gtpv2Ie.PDN_ADDRESS, 0, Gtpv2Ies.encodePaa(r.ue().ipv4())));
        }
        QosProfile q = r.policy().qos();
        ies.add(new Gtpv2Ie(GtpcShapes.AMBR, 0, ambr(q.apnAmbrUlBps(), q.apnAmbrDlBps())));
        List<Gtpv2Ie> bearer = new ArrayList<>();
        bearer.add(GtpcShapes.fteid(2, r.pgwS5u()));
        bearer.add(new Gtpv2Ie(GtpcShapes.BEARER_QOS, 0, bearerQos(q)));
        bearer.add(new Gtpv2Ie(Gtpv2Ie.CHARGING_ID, 0, ByteBuffer.allocate(4)
                .putInt((int) r.chargingId().value()).array()));
        ies.add(GtpcShapes.bearerContext(ebi, GtpcShapes.CAUSE_ACCEPTED, bearer));
        if (!r.pco().isEmpty()) {
            ies.add(Gtpv2Ies.pco(r.pco()));
        }
        return new Gtpv2Message(Gtpv2MessageType.CREATE_SESSION_RESPONSE,
                sgwS5c.teid(), req.sequence(), req.recovery(), ies);
    }

    /** §8.7 AMBR: UL then DL, kbps, 4 octets each. */
    static byte[] ambr(long ulBps, long dlBps) {
        return ByteBuffer.allocate(8).putInt((int) (ulBps / 1000)).putInt((int) (dlBps / 1000)).array();
    }

    /**
     * §8.15 Bearer QoS (22 octets): PCI/PL/PVI, QCI, MBR UL/DL and GBR UL/DL (5 octets each, kbps).
     * Default bearer: no MBR/GBR. PCI bit = 1 means "may NOT pre-empt" (inverse of capability).
     */
    static byte[] bearerQos(QosProfile q) {
        byte[] v = new byte[22];
        int pci = q.preemptionCapability() ? 0 : 1;
        int pvi = q.preemptionVulnerability() ? 0 : 1;
        v[0] = (byte) ((pci << 6) | ((q.arpPriority() & 0x0f) << 2) | pvi);
        v[1] = (byte) q.qci();
        return v;
    }
}
