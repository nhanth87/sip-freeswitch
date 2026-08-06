package et.sipfreeswitch.bootstrap;

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
import et.sipfreeswitch.metrics.SipCpsMetrics;
import et.sipfreeswitch.sbbs.ProxySbb;
import et.sipfreeswitch.sbbs.RegistrationSbb;
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
public final class SipEdgeBootstrap {

    private static final Logger LOG = LogManager.getLogger(SipEdgeBootstrap.class);

    @Inject
    MicroSleeContainer container;

    @Inject
    SipCpsMetrics cpsMetrics;

    @ConfigProperty(name = "sip.ra.host", defaultValue = "0.0.0.0")
    String sipHost;

    @ConfigProperty(name = "sip.ra.udp-port", defaultValue = "5060")
    int udpPort;

    @ConfigProperty(name = "sip.ra.tcp-port", defaultValue = "5060")
    int tcpPort;

    @ConfigProperty(name = "sip.ra.ice-enabled", defaultValue = "false")
    boolean iceEnabled;

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
            config.setStunServer("stun.l.google.com");
            config.setStunPort(3478);
            config.setIceKeepAliveSecs(30);
        }

        SipServletResourceAdaptor ra = new SipServletResourceAdaptor();
        sipEndpoint = new SipServletRaEndpoint(ra);
        sipEndpoint.setConfig(config);
        container.registerRa(sipEndpoint, sipEndpoint);
        LOG.info("SIP RA registered on UDP:{} TCP:{} ice={}", udpPort, tcpPort, iceEnabled);
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
        container.registerSbbType(ProxySbb.class, () -> new ProxySbb(trunk, domain, metrics));
        container.registerSbbType(RegistrationSbb.class, RegistrationSbb::new);
        LOG.info("Registered SBB types: ProxySbb, RegistrationSbb");
    }

    private void mapEventToSbb() {
        container.mapEventToSbb(SipInviteEvent.class, "ProxySbb");
        container.mapEventToSbb(SipResponseEvent.class, "ProxySbb");
        container.mapEventToSbb(SipRegisterEvent.class, "RegistrationSbb");
    }

    private void bindInitialEventSelector() {
        container.createIesDispatcher();
    }
}
