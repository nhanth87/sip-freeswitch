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
import et.elisa.media.MediaIceTurnPolicy;
import et.elisa.metrics.SipCpsMetrics;
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

    private volatile SipServletRaEndpoint sipEndpoint;
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
        wirePrometheusRa();
        mapEventToSbb();
        LOG.info("=== sip-freeswitch Ready — SIP UDP:{} TCP:{} trunk={} ===",
                udpPort, tcpPort, freeswitchTrunkUri);
    }

    @PreDestroy
    void shutdown() {
        if (telemetryPort instanceof MicrometerTelemetryPort mtp) {
            mtp.stop();
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

        SipServletResourceAdaptor ra = new SipServletResourceAdaptor();
        sipEndpoint = new SipServletRaEndpoint(ra);
        sipEndpoint.setConfig(config);
        container.registerRa(sipEndpoint, sipEndpoint);
        LOG.info("SIP RA registered on UDP:{} TCP:{} ice={} stun={}:{} turnRequired={}",
                udpPort, tcpPort, iceEnabled, stunServer, stunPort, mediaPolicy.requireTurn());
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
        boolean preferRelay = media.requireTurn();
        container.registerSbbType(ProxySbb.class, () -> new ProxySbb(trunk, domain, metrics, acl, media));
        container.registerSbbType(RegistrationSbb.class, () -> new RegistrationSbb(acl));
        container.registerSbbType(IceNegotiationSbb.class, () -> new IceNegotiationSbb(preferRelay));
        LOG.info("Registered SBB types: ProxySbb, RegistrationSbb, IceNegotiationSbb preferRelay={}", preferRelay);
    }

    private void mapEventToSbb() {
        container.mapEventToSbb(SipInviteEvent.class, "ProxySbb");
        container.mapEventToSbb(SipResponseEvent.class, "ProxySbb");
        container.mapEventToSbb(SipRegisterEvent.class, "RegistrationSbb");
        container.mapEventToSbb(IceCandidateEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(IceCompletedEvent.class, "IceNegotiationSbb");
        container.mapEventToSbb(IceFailedEvent.class, "IceNegotiationSbb");
    }

    private void bindInitialEventSelector() {
        container.createIesDispatcher();
    }
}
