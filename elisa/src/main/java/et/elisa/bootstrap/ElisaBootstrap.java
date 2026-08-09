package et.elisa.bootstrap;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.DiameterRaConfig;
import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import com.microjainslee.ra.diameter.DiameterResourceAdaptor;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import com.microjainslee.ra.prometheus.PrometheusRaEndpoint;
import com.microjainslee.ra.prometheus.PrometheusResourceAdaptor;
import com.microjainslee.ra.sipservlet.SipRaConfig;
import com.microjainslee.ra.sipservlet.SipServletRaEndpoint;
import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;
import com.microjainslee.ra.sipservlet.events.IceCandidateEvent;
import com.microjainslee.ra.sipservlet.events.IceCompletedEvent;
import com.microjainslee.ra.sipservlet.events.IceFailedEvent;
import com.microjainslee.ra.sipservlet.events.SipInviteEvent;
import com.microjainslee.ra.sipservlet.events.SipRegisterEvent;
import com.microjainslee.ra.sipservlet.events.SipResponseEvent;
import com.microjainslee.telemetry.MicrometerTelemetryPort;
import com.microjainslee.telemetry.TelemetryDispatchObserver;
import com.microjainslee.telemetry.TelemetryPort;
import com.microjainslee.telemetry.TelemetryRaObserver;
import et.elisa.acl.SipIngressAclService;
import et.elisa.cluster.DialogCheckpointStore;
import et.elisa.cluster.FsPairRegistry;
import et.elisa.cmp.DialogCmpPersistence;
import et.elisa.esl.FreeSwitchRaBridge;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.CxClient;
import et.elisa.ims.cx.CxServer;
import et.elisa.ims.cx.DiameterCxSbb;
import et.elisa.ims.cx.DiameterRaBridge;
import et.elisa.ims.hss.HssLiteService;
import et.elisa.ims.icscf.IcscfRoutingSbb;
import et.elisa.ims.pcscf.PcscfProxySbb;
import et.elisa.ims.reg.BindingStore;
import et.elisa.ims.scscf.IscAsRouter;
import et.elisa.ims.scscf.IscThirdPartyRegister;
import et.elisa.ims.scscf.ScscfInviteSbb;
import et.elisa.ims.scscf.ScscfRegisterSbb;
import et.elisa.media.MediaIceTurnPolicy;
import et.elisa.metrics.SipCpsMetrics;
import et.elisa.ra.freeswitch.FreeSwitchRaConfig;
import et.elisa.ra.freeswitch.FreeSwitchRaEndpoint;
import et.elisa.ra.freeswitch.FreeSwitchResourceAdaptor;
import et.elisa.ra.freeswitch.events.FsApiResponseEvent;
import et.elisa.ra.freeswitch.events.FsChannelEvent;
import et.elisa.ra.freeswitch.events.FsHeartbeatEvent;
import et.elisa.sbbs.FreeSwitchMonitorSbb;
import et.elisa.sbbs.IceNegotiationSbb;
import et.elisa.sbbs.ProxySbb;
import et.elisa.sbbs.RegistrationSbb;
import et.elisa.profile.ElisaProfileService;
import et.elisa.session.CallSessionService;
import et.elisa.session.CallSessionSbb;
import et.elisa.recovery.SbbRecoveryManagerImpl;
import com.microjainslee.core.recovery.SessionRecoveryService;
import com.microjainslee.core.recovery.SessionRecoveryServiceImpl;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
final class ElisaBootstrap {

    private static final Logger LOG = LogManager.getLogger(ElisaBootstrap.class);

    @Inject MicroSleeContainer container;
    @Inject SipCpsMetrics cpsMetrics;
    @Inject SipIngressAclService sipAcl;
    @Inject MediaIceTurnPolicy mediaPolicy;
    @Inject FreeSwitchRaBridge freeSwitchRaBridge;
    @Inject FsPairRegistry fsPairRegistry;
    @Inject DialogCheckpointStore dialogCheckpoints;
    @Inject DialogCmpPersistence dialogCmp;
    @Inject ImsRoleFlags imsRoles;
    @Inject CxClient cxClient;
    @Inject CxServer cxServer;
    @Inject HssLiteService hssLite;
    @Inject BindingStore bindingStore;
    @Inject IscAsRouter iscAsRouter;
    @Inject IscThirdPartyRegister thirdParty;
    @Inject DiameterRaBridge diameterRaBridge;
    @Inject CallSessionService callSessionService;
    @Inject ElisaProfileService elisaProfiles;
    @Inject SbbRecoveryManagerImpl sbbRecovery;

    @ConfigProperty(name = "sip.ra.host", defaultValue = "0.0.0.0") String sipHost;
    @ConfigProperty(name = "sip.ra.udp-port", defaultValue = "5060") int udpPort;
    @ConfigProperty(name = "sip.ra.tcp-port", defaultValue = "5060") int tcpPort;
    @ConfigProperty(name = "sip.ra.ice-enabled", defaultValue = "true") boolean iceEnabled;
    @ConfigProperty(name = "sip.ra.stun-server", defaultValue = "stun.l.google.com") String stunServer;
    @ConfigProperty(name = "sip.ra.stun-port", defaultValue = "3478") int stunPort;
    @ConfigProperty(name = "sip.ra.ice-keep-alive-secs", defaultValue = "30") long iceKeepAliveSecs;
    @ConfigProperty(name = "sip.trunk.freeswitch-uri", defaultValue = "sip:1000@127.0.0.1:5062") String freeswitchTrunkUri;
    @ConfigProperty(name = "sip.trunk.domain", defaultValue = "freeswitch.lab") String trunkDomain;
    @ConfigProperty(name = "sf.esl.host", defaultValue = "localhost") String eslHost;
    @ConfigProperty(name = "sf.esl.port", defaultValue = "8021") int eslPort;
    @ConfigProperty(name = "sf.esl.password", defaultValue = "ClueCon") String eslPassword;
    @ConfigProperty(name = "sf.ims.cx.listen-host", defaultValue = "0.0.0.0") String cxHost;
    @ConfigProperty(name = "sf.ims.cx.listen-port", defaultValue = "3868") int cxPort;
    @ConfigProperty(name = "sf.ims.cx.origin-host", defaultValue = "elisa.ims.lab") String cxOriginHost;
    @ConfigProperty(name = "sf.ims.cx.origin-realm", defaultValue = "ims.lab") String cxOriginRealm;
    @ConfigProperty(name = "sf.ims.cx.enabled", defaultValue = "true") boolean cxEnabled;

    private volatile SipServletRaEndpoint sipEndpoint;
    private volatile FreeSwitchRaEndpoint freeSwitchEndpoint;
    private volatile DiameterRaEndpoint diameterEndpoint;
    private volatile TelemetryPort telemetryPort;
    private volatile boolean started;

    void onStart(@Observes StartupEvent ev) {
        if (started) {
            return;
        }
        started = true;
        LOG.info("Elisa IMS Core bootstrap roles={}", imsRoles.summary());
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        elisaProfiles.provisionTables();
        wireTelemetry();
        registerSbbTypes();
        bindInitialEventSelector();
        wireSipRa();
        wireFreeSwitchRa();
        wireDiameterRa();
        wirePrometheusRa();
        mapEventToSbb();
        wireSbbRecovery();
        FsPairRegistry.EslTarget primary = fsPairRegistry.primary().orElse(null);
        LOG.info("=== Elisa Ready — SIP {}:{} IMS[{}] esl={}:{} node={} cxPeerReady={} ===",
                udpPort, tcpPort, imsRoles.summary(),
                primary != null ? primary.host() : eslHost,
                primary != null ? primary.port() : eslPort,
                fsPairRegistry.nodeId(),
                diameterRaBridge.peerReady());
    }

    @PreDestroy
    void shutdown() {
        if (telemetryPort instanceof MicrometerTelemetryPort mtp) {
            mtp.stop();
        }
        if (diameterEndpoint != null) {
            diameterEndpoint.deactivate();
        }
        if (freeSwitchEndpoint != null) {
            freeSwitchEndpoint.deactivate();
        }
        if (sipEndpoint != null) {
            sipEndpoint.deactivate();
        }
        if (container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    private void wireSipRa() {
        SipRaConfig config = new SipRaConfig();
        config.setHost(sipHost);
        config.setUdpPort(udpPort);
        config.setTcpPort(tcpPort);
        config.setSctpPort(0);
        config.setNettyBossThreads(1L);
        config.setNettyWorkerThreads(0L);
        config.setNettySoBacklog(1024L);
        config.setNettyTcpNoDelay(true);
        config.setNettySoKeepAlive(true);
        config.setNettySoRcvBuf(262144);
        config.setNettySoSndBuf(262144);
        config.setDnsEnabled(true);
        config.setDnsCacheTtlSecs(300);
        config.setIceEnabled(iceEnabled);
        if (iceEnabled) {
            config.setStunServer(stunServer);
            config.setStunPort(stunPort);
            config.setIceKeepAliveSecs(iceKeepAliveSecs);
        }
        config.setPreferRelayCandidate(mediaPolicy.rtpRedirect() || mediaPolicy.requireTurn());
        String turnHost = turnHost(mediaPolicy.turnUri());
        if (turnHost != null) {
            config.setTurnServer(turnHost);
            config.setTurnPort(turnPort(mediaPolicy.turnUri(), 3478));
            config.setTurnUsername(mediaPolicy.turnUsername());
            config.setTurnCredential(mediaPolicy.turnCredential());
        }
        SipServletResourceAdaptor ra = new SipServletResourceAdaptor();
        sipEndpoint = new SipServletRaEndpoint(ra);
        sipEndpoint.setConfig(config);
        container.registerRa(sipEndpoint, sipEndpoint);
        sbbRecovery.bindSipRa(ra);
        LOG.info("SIP RA UDP:{} TCP:{} ice={}", udpPort, tcpPort, iceEnabled);
    }

    /**
     * Wire SbbRecoveryManager: EntityRemovalBus (async) + Gap-SR-1 SessionRecoveryService
     * so MISSING_ENTITY rehydrates via CMP restore when JVM snapshots are absent.
     */
    private void wireSbbRecovery() {
        SessionRecoveryService recovery = new SessionRecoveryServiceImpl(snapshot -> {
            if (snapshot == null || snapshot.entityId() == null) {
                return false;
            }
            String callId = snapshot.convergenceKey();
            if (callId == null || callId.isBlank()) {
                Object cid = snapshot.cmpFields().get("sipCallId");
                callId = cid != null ? cid.toString() : null;
            }
            if (callId == null || callId.isBlank()) {
                String id = snapshot.entityId();
                int slash = id.lastIndexOf('/');
                callId = slash >= 0 ? id.substring(slash + 1) : id;
            }
            return sbbRecovery.respawnLocal(
                    et.elisa.recovery.RecoveryKey.sipCallId(callId),
                    et.elisa.recovery.RecoveryCause.MISSING_ENTITY);
        });
        sbbRecovery.bindSessionRecovery(recovery);
        container.getEventRouter().bindSessionRecoveryService(recovery);
        sbbRecovery.attachToContainer();
        LOG.info("SbbRecoveryManager wired (EntityRemovalBus + SessionRecovery Gap-SR-1)");
    }

    private void wireFreeSwitchRa() {
        FsPairRegistry.EslTarget target = fsPairRegistry.primary()
                .orElseGet(() -> new FsPairRegistry.EslTarget("fallback", eslHost, eslPort, eslPassword, 0));
        FreeSwitchRaConfig cfg = fsPairRegistry.toConfig(target);
        FreeSwitchResourceAdaptor ra = new FreeSwitchResourceAdaptor();
        freeSwitchEndpoint = new FreeSwitchRaEndpoint(ra);
        freeSwitchEndpoint.setConfig(cfg);
        container.registerRa(freeSwitchEndpoint, freeSwitchEndpoint);
        freeSwitchRaBridge.bind(ra);
        freeSwitchRaBridge.setActiveFsNodeId(target.fsNodeId());
        LOG.info("FreeSWITCH RA ESL {}:{} fsNode={}", target.host(), target.port(), target.fsNodeId());
    }

    private void wireDiameterRa() {
        if (!cxEnabled || !(imsRoles.icscf() || imsRoles.scscf() || imsRoles.hss())) {
            LOG.info("Diameter RA skipped (cx.enabled={} roles={})", cxEnabled, imsRoles.summary());
            return;
        }
        DiameterRaConfig cfg = new DiameterRaConfig();
        cfg.setHost(cxHost);
        cfg.setPort(cxPort);
        cfg.setOriginHost(cxOriginHost);
        cfg.setRealm(cxOriginRealm);
        cfg.setProductName("elisa-ims-cx");
        DiameterResourceAdaptor ra = new DiameterResourceAdaptor();
        diameterEndpoint = new DiameterRaEndpoint(ra);
        diameterEndpoint.setConfig(cfg);
        container.registerRa(diameterEndpoint, diameterEndpoint);
        diameterRaBridge.bind(diameterEndpoint);
        cxClient.bindDiameter(diameterEndpoint);
        cxServer.bindDiameter(diameterEndpoint);
        LOG.info("Diameter RA Cx {}:{} originHost={} peerReady={}",
                cxHost, cxPort, cxOriginHost, diameterEndpoint.isPeerReady());
    }

    private void wireTelemetry() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MicrometerTelemetryPort micrometer = new MicrometerTelemetryPort(registry, container);
        micrometer.start();
        telemetryPort = micrometer;
        cpsMetrics.bind(registry);
        container.getEventRouter().setDispatchObserver(new TelemetryDispatchObserver(micrometer));
        container.setRaObserver(new TelemetryRaObserver(micrometer));
    }

    private void wirePrometheusRa() {
        var prometheusRa = new PrometheusResourceAdaptor();
        prometheusRa.setPort(9090);
        container.registerRa(new PrometheusRaEndpoint(prometheusRa));
    }

    private void registerSbbTypes() {
        String trunk = freeswitchTrunkUri;
        String domain = trunkDomain;
        SipCpsMetrics metrics = cpsMetrics;
        SipIngressAclService acl = sipAcl;
        MediaIceTurnPolicy media = mediaPolicy;
        DialogCheckpointStore cps = dialogCheckpoints;
        String nodeId = fsPairRegistry.nodeId();
        boolean preferRelay = media.requireTurn();

        container.registerSbbType(ProxySbb.class,
                () -> new ProxySbb(trunk, domain, metrics, acl, media, cps, nodeId,
                        freeSwitchRaBridge.activeFsNodeId() != null
                                ? freeSwitchRaBridge.activeFsNodeId() : "fallback"));
        container.registerSbbType(RegistrationSbb.class, () -> new RegistrationSbb(acl));
        container.registerSbbType(IceNegotiationSbb.class, () -> new IceNegotiationSbb(preferRelay));
        container.registerSbbType(FreeSwitchMonitorSbb.class, FreeSwitchMonitorSbb::new);
        container.registerSbbType(CallSessionSbb.class,
                () -> new CallSessionSbb.$Concrete(callSessionService,
                        freeSwitchRaBridge.activeFsNodeId() != null
                                ? freeSwitchRaBridge.activeFsNodeId() : "fallback"));

        if (imsRoles.scscf()) {
            container.registerSbbType(ScscfRegisterSbb.class,
                    () -> new ScscfRegisterSbb(imsRoles, cxClient, hssLite, bindingStore, thirdParty, elisaProfiles));
            DialogCmpPersistence cmp = dialogCmp;
            container.registerSbbType(ScscfInviteSbb.class,
                    () -> new ScscfInviteSbb.$Concrete(imsRoles, iscAsRouter, bindingStore, cmp, metrics, hssLite,
                            nodeId,
                            freeSwitchRaBridge.activeFsNodeId() != null
                                    ? freeSwitchRaBridge.activeFsNodeId() : "fallback"));
        }
        if (imsRoles.pcscf()) {
            container.registerSbbType(PcscfProxySbb.class, () -> new PcscfProxySbb(imsRoles));
        }
        if (imsRoles.icscf()) {
            container.registerSbbType(IcscfRoutingSbb.class, () -> new IcscfRoutingSbb(imsRoles, cxClient));
        }
        if (cxEnabled && (imsRoles.hss() || imsRoles.icscf() || imsRoles.scscf())) {
            container.registerSbbType(DiameterCxSbb.class, () -> new DiameterCxSbb(cxServer, cxClient));
        }
        LOG.info("SBB types registered IMS={}", imsRoles.summary());
    }

    private void mapEventToSbb() {
        if (imsRoles.scscf()) {
            container.mapEventToSbb(SipRegisterEvent.class, "ScscfRegisterSbb");
            container.mapEventToSbb(SipInviteEvent.class, "ScscfInviteSbb");
            container.mapEventToSbb(SipResponseEvent.class, "ScscfInviteSbb");
        } else if (imsRoles.pcscf()) {
            container.mapEventToSbb(SipRegisterEvent.class, "PcscfProxySbb");
            container.mapEventToSbb(SipInviteEvent.class, "PcscfProxySbb");
            container.mapEventToSbb(SipResponseEvent.class, "PcscfProxySbb");
        } else if (imsRoles.icscf()) {
            container.mapEventToSbb(SipRegisterEvent.class, "IcscfRoutingSbb");
            container.mapEventToSbb(SipInviteEvent.class, "IcscfRoutingSbb");
        } else {
            container.mapEventToSbb(SipInviteEvent.class, "ProxySbb");
            container.mapEventToSbb(SipResponseEvent.class, "ProxySbb");
            container.mapEventToSbb(SipRegisterEvent.class, "RegistrationSbb");
        }
        container.mapEventToSbb(IceCandidateEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(IceCompletedEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(IceFailedEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(FsHeartbeatEvent.class, "CallSessionSbb");
        container.mapEventToSbb(FsChannelEvent.class, "CallSessionSbb");
        container.mapEventToSbb(FsApiResponseEvent.class, "CallSessionSbb");
        if (cxEnabled && (imsRoles.hss() || imsRoles.icscf() || imsRoles.scscf())) {
            container.mapEventToSbb(DiameterRequestEvent.class, "DiameterCxSbb");
            container.mapEventToSbb(DiameterAnswerEvent.class, "DiameterCxSbb");
        }
    }

    private void bindInitialEventSelector() {
        container.createIesDispatcher();
    }

    private static String turnHost(String turnUri) {
        if (turnUri == null || turnUri.isBlank()) {
            return null;
        }
        String s = turnUri.trim();
        if (s.regionMatches(true, 0, "turn:", 0, 5)) {
            s = s.substring(5);
        } else if (s.regionMatches(true, 0, "turns:", 0, 6)) {
            s = s.substring(6);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(']') < 0) {
            s = s.substring(0, colon);
        }
        return s.isBlank() ? null : s;
    }

    private static int turnPort(String turnUri, int defaultPort) {
        if (turnUri == null) {
            return defaultPort;
        }
        String s = turnUri.trim();
        if (s.regionMatches(true, 0, "turn:", 0, 5)) {
            s = s.substring(5);
        } else if (s.regionMatches(true, 0, "turns:", 0, 6)) {
            s = s.substring(6);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && colon < s.length() - 1 && s.indexOf(']') < 0) {
            try {
                return Integer.parseInt(s.substring(colon + 1).replaceAll("[^0-9].*", ""));
            } catch (NumberFormatException ignored) {
                return defaultPort;
            }
        }
        return defaultPort;
    }
}
