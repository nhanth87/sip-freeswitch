package et.elisa.bootstrap;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.prometheus.PrometheusRaEndpoint;
import com.microjainslee.ra.prometheus.PrometheusResourceAdaptor;
import com.microjainslee.ra.sipservlet.SipRaConfig;
import com.microjainslee.ra.sipservlet.SipServletRaEndpoint;
import com.microjainslee.ra.sipservlet.SipServletResourceAdaptor;
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
import et.elisa.esl.FreeSwitchRaBridge;
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
import com.microjainslee.ra.sipservlet.events.IceCandidateEvent;
import com.microjainslee.ra.sipservlet.events.IceCompletedEvent;
import com.microjainslee.ra.sipservlet.events.IceFailedEvent;
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

/**
 * Wires SIP RA + SBBs into MicroSleeContainer. Observes {@link StartupEvent}
 * so the bean is not left lazy.
 */
@ApplicationScoped
final class ElisaBootstrap {

    private static final Logger LOG = LogManager.getLogger(ElisaBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    SipCpsMetrics cpsMetrics;

    @Inject
    SipIngressAclService sipAcl;

    @Inject
    MediaIceTurnPolicy mediaPolicy;

    @Inject
    FreeSwitchRaBridge freeSwitchRaBridge;

    @Inject
    FsPairRegistry fsPairRegistry;

    @Inject
    DialogCheckpointStore dialogCheckpoints;

    @ConfigProperty(name = "sip.ra.host", defaultValue = "0.0.0.0")
    String sipHost;

    @ConfigProperty(name = "sip.ra.udp-port", defaultValue = "5060")
    int udpPort;

    @ConfigProperty(name = "sip.ra.tcp-port", defaultValue = "5060")
    int tcpPort;

    @ConfigProperty(name = "sip.ra.ice-enabled", defaultValue = "true")
    boolean iceEnabled;

    @ConfigProperty(name = "sip.ra.stun-server", defaultValue = "stun.l.google.com")
    String stunServer;

    @ConfigProperty(name = "sip.ra.stun-port", defaultValue = "3478")
    int stunPort;

    @ConfigProperty(name = "sip.ra.ice-keep-alive-secs", defaultValue = "30")
    long iceKeepAliveSecs;

    @ConfigProperty(name = "sip.trunk.freeswitch-uri", defaultValue = "sip:1000@127.0.0.1:5062")
    String freeswitchTrunkUri;

    @ConfigProperty(name = "sip.trunk.domain", defaultValue = "freeswitch.lab")
    String trunkDomain;

    @ConfigProperty(name = "sf.esl.host", defaultValue = "localhost")
    String eslHost;

    @ConfigProperty(name = "sf.esl.port", defaultValue = "8021")
    int eslPort;

    @ConfigProperty(name = "sf.esl.password", defaultValue = "ClueCon")
    String eslPassword;

    private volatile SipServletRaEndpoint sipEndpoint;
    private volatile FreeSwitchRaEndpoint freeSwitchEndpoint;
    private volatile TelemetryPort telemetryPort;
    private volatile boolean started;

    void onStart(@Observes StartupEvent ev) {
        if (started) {
            return;
        }
        started = true;
        LOG.info("sip-freeswitch edge bootstrap (StartupEvent)");
        if (container.getState() != MicroSleeContainer.State.STARTED) {
            container.start();
        }
        wireTelemetry();
        registerSbbTypes();
        bindInitialEventSelector();
        wireSipRa();
        wireFreeSwitchRa();
        wirePrometheusRa();
        mapEventToSbb();
        FsPairRegistry.EslTarget primary = fsPairRegistry.primary().orElse(null);
        LOG.info("=== sip-freeswitch Ready — SIP UDP:{} TCP:{} trunk={} esl={}:{} node={} fsNode={} checkpoints={} ===",
                udpPort, tcpPort, freeswitchTrunkUri,
                primary != null ? primary.host() : eslHost,
                primary != null ? primary.port() : eslPort,
                fsPairRegistry.nodeId(),
                primary != null ? primary.fsNodeId() : "fallback",
                dialogCheckpoints.enabled());
    }

    @PreDestroy
    void shutdown() {
        if (telemetryPort instanceof MicrometerTelemetryPort mtp) {
            mtp.stop();
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
        // rtp_redirect: prefer relay ordering; real TURN ALLOCATE stays on UA/coturn
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
        LOG.info("SIP RA registered on UDP:{} TCP:{} ice={} stun={}:{} rtpRedirect={} turnHost={}",
                udpPort, tcpPort, iceEnabled, stunServer, stunPort,
                mediaPolicy.rtpRedirect(), turnHost);
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
        LOG.info("FreeSWITCH RA registered ESL {}:{} fsNode={} name={} (pair size={})",
                target.host(), target.port(), target.fsNodeId(), freeSwitchEndpoint.getRaName(),
                fsPairRegistry.targetsForThisNode().size());
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
        String fsNode = freeSwitchRaBridge.activeFsNodeId() != null
                ? freeSwitchRaBridge.activeFsNodeId()
                : fsPairRegistry.primary().map(FsPairRegistry.EslTarget::fsNodeId).orElse("fallback");
        boolean preferRelay = media.requireTurn();
        container.registerSbbType(ProxySbb.class,
                () -> new ProxySbb(trunk, domain, metrics, acl, media, cps, nodeId,
                        freeSwitchRaBridge.activeFsNodeId() != null
                                ? freeSwitchRaBridge.activeFsNodeId() : fsNode));
        container.registerSbbType(RegistrationSbb.class, () -> new RegistrationSbb(acl));
        container.registerSbbType(IceNegotiationSbb.class, () -> new IceNegotiationSbb(preferRelay));
        container.registerSbbType(FreeSwitchMonitorSbb.class, FreeSwitchMonitorSbb::new);
        LOG.info("Registered SBB types: ProxySbb, RegistrationSbb, IceNegotiationSbb, FreeSwitchMonitorSbb preferRelay={} node={}",
                preferRelay, nodeId);
    }

    private void mapEventToSbb() {
        container.mapEventToSbb(SipInviteEvent.class, "ProxySbb");
        container.mapEventToSbb(SipResponseEvent.class, "ProxySbb");
        container.mapEventToSbb(SipRegisterEvent.class, "RegistrationSbb");
        container.mapEventToSbb(IceCandidateEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(IceCompletedEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(IceFailedEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(FsHeartbeatEvent.class, "FreeSwitchMonitorSbb");
        container.mapEventToSbb(FsChannelEvent.class, "FreeSwitchMonitorSbb");
        container.mapEventToSbb(FsApiResponseEvent.class, "FreeSwitchMonitorSbb");
    }

    private void bindInitialEventSelector() {
        container.createIesDispatcher();
    }
}
