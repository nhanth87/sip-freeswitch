package et.epc.host;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import com.microjainslee.ra.diameter.DiameterResourceAdaptor;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.api.PlmnId;
import et.epc.api.cluster.DualWriteSessionStore;
import et.epc.api.cluster.DurableSink;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.api.cluster.JdbcDurableSink;
import et.epc.api.cluster.SessionCheckpoint;
import et.epc.api.cluster.SessionStore;
import com.microjainslee.ra.gtpv2c.Gtpv2RaEndpoint;
import com.microjainslee.ra.gtpv2c.event.GtpEchoEvent;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import et.epc.host.sbb.DiameterGatewaySbb;
import et.epc.host.sbb.GtpGatewaySbb;
import et.epc.hss.CxService;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HssRepository;
import et.epc.hss.HssService;
import et.epc.hss.JdbcHssRepository;
import et.epc.hss.JdbcSecretStore;
import et.epc.hss.MmeAllowlist;
import et.epc.hss.SecretStore;
import et.epc.pcef.JdbcCdrStore;
import et.epc.pcef.PcefService;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import com.microjainslee.ra.pfcp.event.PfcpAssociationEvent;
import com.microjainslee.ra.pfcp.event.PfcpHeartbeatEvent;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.pcrf.JdbcPolicyRepository;
import et.epc.pcrf.PccCache;
import et.epc.pcrf.PccRule;
import et.epc.pcrf.PcrfService;
import et.epc.pcrf.sbbs.PcrfSbb;
import et.epc.pgwc.GxWireConfig;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import et.epc.pgwc.WirePcrfClient;
import et.epc.profile.EpcProfiles;
import et.epc.sgwc.SgwControl;
import et.epc.up.linux.HeapUrrDatastore;
import et.epc.up.linux.LinuxGtpAgent;
import et.epc.up.linux.NftUrrSampler;
import et.epc.up.linux.PfcpReportSink;
import et.epc.up.linux.ProcessRunner;
import et.epc.up.linux.ReportSink;
import et.epc.up.linux.UrrCounterSampler;
import et.epc.up.linux.UrrDatastore;
import et.epc.up.linux.UrrIpIndex;
import et.epc.up.linux.UrrPoller;
import et.epc.up.linux.app.UpfApp;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.infinispan.Cache;

/**
 * Wires RAs by role flag. Connection stays on the RA; procedures on SBBs.
 * <ul>
 *   <li>Diameter S6a/Cx/Gx/Gy → vendor {@code diameter-ra} only</li>
 *   <li>SIP Gm/Mw/ISC → Elisa {@code sip-servlet-ra} only — never registered here</li>
 *   <li>GTP-C / PFCP → internal {@code gtpv2c-ra} / {@code pfcp-ra}</li>
 * </ul>
 */
public final class EpcBootstrap {

    public static final String SIP_SERVLET_RA = "sip-servlet-ra";
    public static final String DIAMETER_RA = "diameter-ra";

    private static final Logger LOG = LogManager.getLogger(EpcBootstrap.class);

    private final EpcRoles roles;
    private final EpcClusterConfig cluster;
    private final List<String> wired = new ArrayList<>();
    private Gtpv2RaEndpoint gtp;
    private PfcpRaEndpoint pfcp;
    private S6aPeerWatch s6a;
    private DiameterPeerPort diameter;
    private SessionStore sessions;
    private HssRepository hssRepo;
    private HssService hssService;
    private CxService cxService;
    private SgwControl sgw;
    private PgwControl pgw;
    private DataSource dataSource;
    private EpcInfinispanManager ispn;
    private String kekHex = "000102030405060708090a0b0c0d0e0f";
    private DiameterSeam diameterSeam = DiameterSeam.DEFAULT;
    private ExecutorService durableExecutor;
    private PccCache pcc;
    private PcefService pcefService;
    private PcrfService pcrfService;
    /** Gx wire client (Phase C) — null unless EPC_PCRF_WIRE_MODE=wire. */
    private volatile WirePcrfClient gxWireClient;
    /** MME peer-trust drawbridge (Open5GS #4337); disabled keeps pre-change behaviour. */
    private MmeAllowlist mmeAllowlist = MmeAllowlist.disabled();
    private MicroSleeContainer slee;
    private boolean s5cWire;
    private String s5cPgwcPeerHost = "127.0.0.1";
    private int s5cPgwcPeerPort = 2123;

    // UP role (epc-up-linux) wiring: URR charging loop + QER gate. Defaults mirror
    // the product config (epc.up.*): URR poll on, QER off until explicitly enabled.
    private String upUrrMode = "off";
    private long upUrrPollMs = 60_000L;
    private long upUrrBaselinePersistMs = 300_000L;
    private boolean upQerEnabled;
    private InetSocketAddress upCpPeer = new InetSocketAddress("127.0.0.1", 8805);
    private LinuxGtpAgent upAgent;
    private UrrIpIndex upIpIndex;
    private UrrPoller urrPoller;

    public EpcBootstrap(EpcRoles roles, EpcClusterConfig cluster) {
        this.roles = roles;
        this.cluster = cluster;
    }

    /** Split S5-C wire seam: when true, SGW-C sends CREATE_SESSION_REQUEST to the PGW-C peer. */
    public EpcBootstrap withS5c(boolean wire, String pgwcPeerHost, int pgwcPeerPort) {
        this.s5cWire = wire;
        if (pgwcPeerHost != null && !pgwcPeerHost.isBlank()) {
            this.s5cPgwcPeerHost = pgwcPeerHost.trim();
        }
        if (pgwcPeerPort > 0 && pgwcPeerPort <= 65_535) {
            this.s5cPgwcPeerPort = pgwcPeerPort;
        }
        return this;
    }

    /**
     * UP role knobs (epc.up.*): URR mode {@code poll|off}, poll cadence, baseline
     * persist cadence, QER gate, and the CP peer addressed by Session Reports.
     */
    public EpcBootstrap withUp(String urrMode, long pollMs, long persistMs,
                               boolean qerEnabled, InetSocketAddress cpPeer) {
        if (urrMode != null && !urrMode.isBlank()) {
            this.upUrrMode = urrMode.trim();
        }
        if (pollMs > 0) {
            this.upUrrPollMs = pollMs;
        }
        if (persistMs >= 0) {
            this.upUrrBaselinePersistMs = persistMs;
        }
        this.upQerEnabled = qerEnabled;
        if (cpPeer != null) {
            this.upCpPeer = cpPeer;
        }
        return this;
    }

    public EpcBootstrap withDataSource(DataSource ds) {
        this.dataSource = ds;
        return this;
    }

    /** Infinispan control-plane manager (nullable): disabled in the heap path. */
    public EpcBootstrap withInfinispan(EpcInfinispanManager manager) {
        this.ispn = manager;
        return this;
    }

    /** HSS/AuC secret KEK (32-hex-char); only used when a datasource is wired. */
    public EpcBootstrap withSecretKek(String kek) {
        if (kek != null && !kek.isBlank()) {
            this.kekHex = kek;
        }
        return this;
    }

    /** MME peer-trust drawbridge (Open5GS #4337). Null keeps the disabled default. */
    public EpcBootstrap withMmeAllowlist(MmeAllowlist allowlist) {
        if (allowlist != null) {
            this.mmeAllowlist = allowlist;
        }
        return this;
    }

    /** Outbound Diameter peer seam (S6a→MME / Cx→Elisa / Gx/Gy). Null keeps the default. */
    public EpcBootstrap withDiameter(DiameterSeam seam) {
        if (seam != null) {
            this.diameterSeam = seam;
        }
        return this;
    }

    /** True when the production Postgres datastore is wired (not heap). */
    public boolean datastoreEnabled() {
        return dataSource != null;
    }

    /** Product HSS JDBC is PostgreSQL on {@code postgres-epc}/{@code epc} only. */
    public EpcBootstrap withJdbcUrl(String jdbcUrl) {
        EpcJdbc.requirePostgres(jdbcUrl);
        return this;
    }

    /**
     * Test / RA-only path: activate endpoints on the given bootstrap and do
     * <em>not</em> attach SBBs. Prefer {@link #attachTo(MicroSleeContainer)}
     * for the host.
     */
    public void start(RaBootstrapPort sleePort) {
        buildPlane();
        if (gtp != null) {
            gtp.activate(sleePort);
        }
        if (pfcp != null) {
            pfcp.activate(sleePort);
        }
        denySip();
        startUpPoller("ra-only");
        LOG.info("epc-host node={} mode={} wired={} (RA-only bootstrap)",
                cluster.nodeId(), cluster.mode(), wired);
    }

    /** Start the URR poller once (mode=poll + UP role); safe to call on both boot paths. */
    private void startUpPoller(String path) {
        if (urrPoller != null && !urrPoller.running()) {
            urrPoller.start();
            LOG.info("epc-host URR poller started mode={} interval={}ms path={}",
                    upUrrMode, upUrrPollMs, path);
        }
    }

    /**
     * Host path: RAs register on the container (sockets + codec), SBBs own
     * procedures, Profiles hold provisioned rows, CMP holds in-flight TEID/SEID.
     */
    public void attachTo(MicroSleeContainer container) {
        this.slee = container;
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        buildPlane();
        ProfileFacility pf = container.getProfileFacility();
        EpcProfiles.provision(pf);
        try {
            EpcProfiles.upsertApn(pf, "internet", "IPV4V6", "10.64.0.0/16");
            EpcProfiles.upsertApn(pf, "ims", "IPV4V6", "10.65.0.0/16");
        } catch (RuntimeException e) {
            LOG.warn("APN Profile seed skipped: {}", e.toString());
        }
        if (roles.wireDiameter()) {
            PcrfService service = (pcrfService != null)
                    ? pcrfService
                    : PcrfService.withHeapPolicy();
            PcrfSbb.$Concrete pcrfSbb = new PcrfSbb.$Concrete(service);
            container.registerSbbType(DiameterGatewaySbb.class,
                    () -> new DiameterGatewaySbb.$Concrete(hssService, cxService, pcrfSbb::handle)
                            .withAnswerSink(this::routeDiameterAnswer));
            container.mapEventToSbb(DiameterRequestEvent.class, "DiameterGatewaySbb");
            // Gx CCA completions for outbound CCRs (Phase C wire seam).
            container.mapEventToSbb(DiameterAnswerEvent.class, "DiameterGatewaySbb");
        }
        if (roles.wireGtpS11() || roles.wireGtpS5()) {
            container.registerSbbType(GtpGatewaySbb.class,
                    () -> new GtpGatewaySbb.$Concrete(sgw, pgw, pf));
            container.mapEventToSbb(Gtpv2MessageEvent.class, "GtpGatewaySbb");
            container.mapEventToSbb(GtpEchoEvent.class, "GtpGatewaySbb");
            container.mapEventToSbb(PfcpMessageEvent.class, "GtpGatewaySbb");
            container.mapEventToSbb(PfcpAssociationEvent.class, "GtpGatewaySbb");
            container.mapEventToSbb(PfcpHeartbeatEvent.class, "GtpGatewaySbb");
        }
        if (gtp != null) {
            container.registerRa(gtp, gtp);
        }
        if (pfcp != null) {
            container.registerRa(pfcp, pfcp);
        }
        if (roles.wireUp() && upAgent != null) {
            // NB: on a combined CP+UP node this maps PfcpMessageEvent to UpfPfcpSbb,
            // overriding the GtpGatewaySbb mapping — roles stay split in deploy.
            new UpfApp().install(container, upAgent, upIpIndex);
            wired.add("up-app");
        }
        if (roles.wireDiameter()) {
            wireDiameterRaOn(container);
        }
        denySip();
        startUpPoller("slee");
        LOG.info("epc-host node={} mode={} wired={} slee=true",
                cluster.nodeId(), cluster.mode(), wired);
    }

    public void bindTransport(InetSocketAddress gtpBind, InetSocketAddress pfcpBind) {
        if (gtp != null && gtpBind != null) {
            gtp.bindUdp(gtpBind);
        }
        if (pfcp != null && pfcpBind != null) {
            pfcp.bindUdp(pfcpBind);
        }
        probePeers();
    }

    /**
     * Fire the boot-time peer probes once the Sockets are bound. PFCP association
     * and (wire mode) GTP Echo give the status board honest peer evidence instead
     * of a bare LISTEN.
     */
    public void probePeers() {
        if (sgw != null) {
            sgw.probePeers();
        }
        if (pgw != null) {
            pgw.probePeers();
        }
    }

    private void buildPlane() {
        boolean db = dataSource != null;
        boolean ispnOn = ispn != null && ispn.enabled();
        Cache<String, CachingHssRepository.CachedSubscriber> subscribers =
                ispnOn ? ispn.<String, CachingHssRepository.CachedSubscriber>cache(
                        EpcInfinispanManager.CACHE_SUBSCRIBERS) : null;
        Cache<String, SessionCheckpoint> sessionsCache =
                ispnOn ? ispn.cache(EpcInfinispanManager.CACHE_SESSIONS) : null;
        Cache<String, List<PccRule>> pccCache =
                ispnOn ? ispn.cache(EpcInfinispanManager.CACHE_PCC) : null;

        if (db) {
            durableExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "epc-durable-sink");
                t.setDaemon(true);
                return t;
            });
            SessionStore hot = sessionsCache != null
                    ? new IspnSessionStore(sessionsCache)
                    : new HeapSessionStore();
            DurableSink sink = new JdbcDurableSink(dataSource);
            sessions = new DualWriteSessionStore(hot, sink, durableExecutor);
            wired.add("sessions-durable");
            if (pccCache != null) {
                pcc = new IspnPccCache(pccCache, new JdbcPolicyRepository(dataSource));
                wired.add("pcc-postgres");
            }
        } else {
            sessions = new HeapSessionStore();
        }
        pcrfService = pcc != null
                ? new PcrfService(pcc)
                : PcrfService.withHeapPolicy();
        wired.add("pcrf");

        if (db) {
            HssRepository durable = new JdbcHssRepository(dataSource);
            hssRepo = subscribers != null
                    ? new CachingHssRepository(durable, subscribers)
                    : durable;
            if (hssRepo instanceof CachingHssRepository caching) {
                caching.warm();
                wired.add("hss-cache-warm");
            }
            wired.add("hss-postgres");
        } else if (roles.wireS6a()) {
            hssRepo = new HeapHssRepository();
            wired.add("hss-heap");
        }
        if (hssRepo != null) {
            SecretStore secrets = db
                    ? new JdbcSecretStore(dataSource, kekHex)
                    : new HeapSecretStore();
            hssService = new HssService(hssRepo, secrets, new PlmnId("001", "01"),
                    mmeAllowlist, "mme.epc.lab");
            cxService = new CxService(hssRepo);
            wired.add(db ? "secrets-postgres" : "secrets-heap");
        }
        InetAddress local;
        try {
            local = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        InetSocketAddress up = new InetSocketAddress("127.0.0.1", 8805);
        if (roles.wireDiameter()) {
            s6a = new S6aPeerWatch();
            s6a.bound();
            if (!wired.contains(DIAMETER_RA)) {
                wired.add(DIAMETER_RA);
            }
        }
        if (roles.wireGtpS11() || roles.wireGtpS5()) {
            gtp = new Gtpv2RaEndpoint();
            wired.add(Gtpv2RaEndpoint.RA_NAME);
        }
        if (roles.wirePfcpSxa() || roles.wirePfcpSxb() || roles.wireUp()) {
            pfcp = new PfcpRaEndpoint();
            wired.add(PfcpRaEndpoint.RA_NAME);
        }
        if (roles.wirePcef() || roles.wirePcefStandalone() || roles.wireGtpS11()) {
            pcefService = db ? new PcefService(new JdbcCdrStore(dataSource)) : new PcefService();
        }
        if (roles.wirePcef() || roles.wirePcefStandalone()) {
            if (roles.wirePcef()) {
                pgw = new PgwControl(cluster, new Ipam(), pcefService, sessions, pfcp, local, up);
            }
            wired.add("pcef");
        }
        if (roles.wireGtpS11()) {
            if (pgw == null) {
                pgw = new PgwControl(cluster, new Ipam(), pcefService, sessions, pfcp, local, up);
            }
            sgw = new SgwControl(cluster, sessions, pgw, pfcp, local, local, up,
                    gtp, s5cWire, new InetSocketAddress(s5cPgwcPeerHost, s5cPgwcPeerPort));
        }
        if (roles.wireUp()) {
            // UP role hosted here: LinuxGtpAgent + URR charging loop. GTP-U stays
            // non-JVM; the poller samples kernel nft counters off the hot path.
            UrrDatastore urrStore = db ? new JdbcUrrDatastore(dataSource) : new HeapUrrDatastore();
            upAgent = new LinuxGtpAgent("gtp0", urrStore, upUrrBaselinePersistMs)
                    .withQerEnabled(upQerEnabled);
            upIpIndex = new UrrIpIndex();
            if ("poll".equalsIgnoreCase(upUrrMode)) {
                ReportSink sink = new PfcpReportSink(pfcp, upCpPeer);
                UrrCounterSampler sampler = new NftUrrSampler(ProcessRunner.processBuilder(),
                        NftUrrSampler.DEFAULT_TABLE, NftUrrSampler.DEFAULT_SET, upIpIndex);
                urrPoller = new UrrPoller(upAgent.urr(), sampler, upUrrPollMs, sink);
            }
            wired.add(db ? "up-urr-postgres" : "up-urr-heap");
            if (!wired.contains("up")) {
                wired.add("up");
            }
        }
    }

    private void denySip() {
        if (wired.contains(SIP_SERVLET_RA)) {
            throw new IllegalStateException("epc-host must not wire " + SIP_SERVLET_RA);
        }
    }

    /**
     * Vendor ra-diameter. SCTP preferred for S6a when the stack enables it;
     * peer live = {@link DiameterRaEndpoint#isPeerReady()} only.
     */
    public void wireDiameterRa(RaBootstrapPort sleePort) {
        DiameterRaEndpoint endpoint = newDiameterEndpoint();
        endpoint.activate(sleePort);
        bindDiameter(new DiameterPeerPort(endpoint));
    }

    private void wireDiameterRaOn(MicroSleeContainer container) {
        DiameterRaEndpoint endpoint = newDiameterEndpoint();
        container.registerRa(endpoint, endpoint);
        bindDiameter(new DiameterPeerPort(endpoint));
        // Phase C Gx wire seam: CCR-I from PCEF to the PCRF peer over Diameter.
        // In-process (default) stays as-is; wire mode requires EPC_PCRF_WIRE_MODE=wire
        // and only goes live when the peer CEA-completes (isPeerReady inside the client).
        if (roles.wirePcef() && pcefService != null) {
            GxWireConfig gxWire = GxWireConfig.fromEnv();
            if (gxWire.wire()) {
                gxWireClient = new WirePcrfClient(endpoint, endpoint::isPeerReady, gxWire,
                        cluster.nodeId() + ".epc.lab");
                pcefService.setPcrfClient(gxWireClient);
                pcefService.setPcrfLive(true);
                LOG.info("Gx wire seam configured peer={}:{} ccr-timeout={}ms",
                        gxWire.peerHost(), gxWire.peerPort(), gxWire.ccrTimeoutMs());
            }
        }
    }

    /** Routes inbound Diameter answers (Gx CCA) to the wire client; no-op when unwired. */
    private void routeDiameterAnswer(DiameterAnswerEvent ans) {
        WirePcrfClient client = gxWireClient;
        if (client != null) {
            client.onCca(ans);
        }
    }

    private DiameterRaEndpoint newDiameterEndpoint() {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("0.0.0.0");
        cfg.setPort(3868);
        cfg.setOriginHost(diameterSeam.originHost() != null && !diameterSeam.originHost().isBlank()
                ? diameterSeam.originHost()
                : cluster.nodeId() + ".epc.lab");
        cfg.setRealm(diameterSeam.originRealm());
        cfg.setProductName("epc-diameter");
        // Connection stays in diameter-ra (TCP today; SCTP/Corsac when that stack is live).
        cfg.setSctpEnabled(false);
        cfg.setTcpEnabled(true);
        // Outbound peer seam: initiate CER to a real Diameter peer (MME for S6a,
        // Elisa for Cx, AF for Rx, OCS for Gy) instead of listen-only 0.0.0.0:3868.
        // live() == isPeerReady() (CEA accepted) — never "socket bound".
        cfg.setPeerRole(diameterSeam.peerRole());
        if (diameterSeam.hasPeer()) {
            cfg.setPeerHost(diameterSeam.peerHost().trim());
            cfg.setPeerPort(diameterSeam.peerPort());
        }
        if (diameterSeam.destinationHost() != null && !diameterSeam.destinationHost().isBlank()) {
            cfg.setDestinationHost(diameterSeam.destinationHost());
        }
        if (diameterSeam.destinationRealm() != null && !diameterSeam.destinationRealm().isBlank()) {
            cfg.setDestinationRealm(diameterSeam.destinationRealm());
        }
        DiameterResourceAdaptor ra = new DiameterResourceAdaptor();
        DiameterRaEndpoint endpoint = new DiameterRaEndpoint(ra);
        endpoint.setConfig(cfg);
        return endpoint;
    }

    /** Tests / CDI can inject an already-constructed vendor endpoint. */
    public void bindDiameter(DiameterPeerPort port) {
        this.diameter = port;
        // Route server-initiated S6a (CLR/PUR/IDR) and Cx (RTR/PPR) through the
        // Diameter RA command-port seam once the peer peer is bound.
        if (hssService != null) {
            hssService.withSender(diameter::send);
        }
        if (cxService != null) {
            cxService.withSender(diameter::send);
        }
        s6a = new S6aPeerWatch();
        s6a.bound();
        if (port.live()) {
            s6a.ceaAccepted("isPeerReady");
        } else {
            s6a.refreshFrom(port);
        }
        if (!wired.contains(DIAMETER_RA)) {
            wired.add(DIAMETER_RA);
        }
    }

    public void stop() {
        if (urrPoller != null) {
            urrPoller.stop();
            urrPoller = null;
        }
        if (upAgent != null) {
            // Flush in-memory baselines (throttled persists may lag) before teardown.
            upAgent.urr().persist();
        }
        if (diameter != null) {
            diameter.endpoint().deactivate();
        }
        if (gtp != null) {
            gtp.deactivate();
        }
        if (pfcp != null) {
            pfcp.deactivate();
        }
        if (durableExecutor != null) {
            durableExecutor.shutdown();
            durableExecutor = null;
        }
    }

    public List<String> wired() {
        return List.copyOf(wired);
    }

    public Gtpv2RaEndpoint gtp() {
        return gtp;
    }

    public PfcpRaEndpoint pfcp() {
        return pfcp;
    }

    public S6aPeerWatch s6a() {
        return s6a;
    }

    public DiameterPeerPort diameter() {
        return diameter;
    }

    public SessionStore sessions() {
        return sessions;
    }

    public PccCache pcc() {
        return pcc;
    }

    public PcrfService pcrfService() {
        return pcrfService;
    }

    public PcefService pcefService() {
        return pcefService;
    }

    public HssRepository hssRepo() {
        return hssRepo;
    }

    public SgwControl sgw() {
        return sgw;
    }

    public PgwControl pgw() {
        return pgw;
    }

    /** UP-role Linux GTP agent, or {@code null} when the UP role is off. */
    public LinuxGtpAgent upAgent() {
        return upAgent;
    }

    /** URR poller, or {@code null} unless mode=poll and the UP role is on. */
    public UrrPoller urrPoller() {
        return urrPoller;
    }

    /** UE-IP → URR attribution index used by the UP role, or {@code null}. */
    public UrrIpIndex urrIpIndex() {
        return upIpIndex;
    }

    public MicroSleeContainer slee() {
        return slee;
    }

    public EpcRoles roles() {
        return roles;
    }

    public DiameterSeam diameterSeam() {
        return diameterSeam;
    }

    public EpcClusterConfig cluster() {
        return cluster;
    }

    public static RaBootstrapPort loggingBootstrap() {
        return new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                LOG.debug("fire {} on {}", event.getClass().getSimpleName(), handle.getId());
            }
        };
    }
}
