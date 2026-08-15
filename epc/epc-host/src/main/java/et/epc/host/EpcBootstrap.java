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
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.api.PlmnId;
import et.epc.api.cluster.EpcClusterConfig;
import et.epc.api.cluster.HeapSessionStore;
import et.epc.api.cluster.SessionStore;
import com.microjainslee.ra.gtpv2c.Gtpv2RaEndpoint;
import com.microjainslee.ra.gtpv2c.event.Gtpv2MessageEvent;
import et.epc.host.sbb.DiameterGatewaySbb;
import et.epc.host.sbb.GtpGatewaySbb;
import et.epc.hss.CxService;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HssRepository;
import et.epc.hss.HssService;
import et.epc.hss.JdbcHssRepository;
import et.epc.pcef.PcefService;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import com.microjainslee.ra.pfcp.event.PfcpMessageEvent;
import et.epc.pgwc.Ipam;
import et.epc.pgwc.PgwControl;
import et.epc.profile.EpcProfiles;
import et.epc.sgwc.SgwControl;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private MicroSleeContainer slee;

    public EpcBootstrap(EpcRoles roles, EpcClusterConfig cluster) {
        this.roles = roles;
        this.cluster = cluster;
    }

    public EpcBootstrap withDataSource(DataSource ds) {
        this.dataSource = ds;
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
        LOG.info("epc-host node={} mode={} wired={} (RA-only bootstrap)",
                cluster.nodeId(), cluster.mode(), wired);
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
        if (roles.wireS6a() || roles.wirePcef()) {
            container.registerSbbType(DiameterGatewaySbb.class,
                    () -> new DiameterGatewaySbb.$Concrete(hssService, cxService));
            container.mapEventToSbb(DiameterRequestEvent.class, "DiameterGatewaySbb");
        }
        if (roles.wireGtpS11() || roles.wireGtpS5()) {
            container.registerSbbType(GtpGatewaySbb.class,
                    () -> new GtpGatewaySbb.$Concrete(sgw, pgw, pf));
            container.mapEventToSbb(Gtpv2MessageEvent.class, "GtpGatewaySbb");
            container.mapEventToSbb(PfcpMessageEvent.class, "GtpGatewaySbb");
        }
        if (gtp != null) {
            container.registerRa(gtp, gtp);
        }
        if (pfcp != null) {
            container.registerRa(pfcp, pfcp);
        }
        if (roles.wireS6a() || roles.wirePcef()) {
            wireDiameterRaOn(container);
        }
        denySip();
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
    }

    private void buildPlane() {
        sessions = new HeapSessionStore();
        if (dataSource != null) {
            hssRepo = new JdbcHssRepository(dataSource);
            wired.add("hss-postgres");
        } else if (roles.wireS6a()) {
            hssRepo = new HeapHssRepository();
            wired.add("hss-heap");
        }
        if (hssRepo != null) {
            hssService = new HssService(hssRepo, new HeapSecretStore(), new PlmnId("001", "01"));
            cxService = new CxService(hssRepo);
        }
        InetAddress local;
        try {
            local = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        InetSocketAddress up = new InetSocketAddress("127.0.0.1", 8805);
        if (roles.wireS6a() || roles.wirePcef()) {
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
        if (roles.wirePfcpSxa() || roles.wirePfcpSxb()) {
            pfcp = new PfcpRaEndpoint();
            wired.add(PfcpRaEndpoint.RA_NAME);
        }
        if (roles.wirePcef()) {
            pgw = new PgwControl(cluster, new Ipam(), new PcefService(), sessions, pfcp, local, up);
            wired.add("pcef");
        }
        if (roles.wireGtpS11()) {
            if (pgw == null) {
                pgw = new PgwControl(cluster, new Ipam(), new PcefService(), sessions, pfcp, local, up);
            }
            sgw = new SgwControl(cluster, sessions, pgw, pfcp, local, local, up);
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
    }

    private DiameterRaEndpoint newDiameterEndpoint() {
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost("0.0.0.0");
        cfg.setPort(3868);
        cfg.setOriginHost(cluster.nodeId() + ".epc.lab");
        cfg.setRealm("epc.lab");
        cfg.setProductName("epc-diameter");
        // Connection stays in diameter-ra (TCP today; SCTP/Corsac when that stack is live).
        cfg.setSctpEnabled(false);
        cfg.setTcpEnabled(true);
        DiameterResourceAdaptor ra = new DiameterResourceAdaptor();
        DiameterRaEndpoint endpoint = new DiameterRaEndpoint(ra);
        endpoint.setConfig(cfg);
        return endpoint;
    }

    /** Tests / CDI can inject an already-constructed vendor endpoint. */
    public void bindDiameter(DiameterPeerPort port) {
        this.diameter = port;
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
        if (diameter != null) {
            diameter.endpoint().deactivate();
        }
        if (gtp != null) {
            gtp.deactivate();
        }
        if (pfcp != null) {
            pfcp.deactivate();
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

    public HssRepository hssRepo() {
        return hssRepo;
    }

    public SgwControl sgw() {
        return sgw;
    }

    public PgwControl pgw() {
        return pgw;
    }

    public MicroSleeContainer slee() {
        return slee;
    }

    public EpcRoles roles() {
        return roles;
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
