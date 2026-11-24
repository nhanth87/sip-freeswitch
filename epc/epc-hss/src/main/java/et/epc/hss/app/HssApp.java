package et.epc.hss.app;

import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;
import com.microjainslee.ra.jss7.event.Ss7MapEvent;
import et.epc.hss.PcrfPort;
import et.epc.hss.sbbs.HlrSbb;
import et.epc.hss.sbbs.HssSbb;
import et.epc.hss.sbbs.UdmUeauSbb;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * micro-jainslee application bootstrap for the HSS process: one SBB type per face, all three on
 * the same AuC ({@link HssBootstrap.Services}).
 *
 * <table>
 *   <caption>Faces</caption>
 *   <tr><th>Face</th><th>RA ({@code @InjectRa})</th><th>Event</th><th>SBB</th></tr>
 *   <tr><td>{@link Face#DIAMETER}</td><td>{@code diameter-ra}</td><td>{@link DiameterRequestEvent}</td>
 *       <td>{@link HssSbb} (S6a / Cx / Gx / Rx)</td></tr>
 *   <tr><td>{@link Face#SS7}</td><td>{@code ra-jss7}</td><td>{@link Ss7MapEvent.Service},
 *       {@link Ss7MapEvent.Dialog}</td><td>{@link HlrSbb} (MAP SendAuthenticationInfo)</td></tr>
 *   <tr><td>{@link Face#SBI}</td><td>{@code http-server-ra}</td><td>{@link HttpWebRequestEvent}</td>
 *       <td>{@link UdmUeauSbb} (Nudm-UEAU)</td></tr>
 * </table>
 *
 * <p>Every SBB instance comes from the {@code registerSbbType} factory, which hands it the real
 * services. None of the three SBB classes has a no-arg constructor, so the container's APT
 * auto-deploy can never create a service-less instance that would shadow the factory for
 * {@code mapEventToSbb} (see {@code HssAppTest}).
 *
 * <p>{@link #install(MicroSleeContainer, HssBootstrap.Services, PcrfPort, Set)} takes the faces to
 * install because micro-jainslee validates, at {@code start()}, that every registered SBB type's
 * {@code @InjectRa} name has a registered RA (strict mode): a face installed without its link
 * aborts the start. {@code HssMain} therefore installs exactly the faces whose link is enabled.
 * {@link #wired()} records what was bound so tests can assert the install without reaching into
 * the container's internal maps.
 */
public final class HssApp {

    /** The three HSS faces; each needs its own RA. */
    public enum Face { DIAMETER, SS7, SBI }

    public static final String SBB_NAME = "HssSbb";
    public static final String HLR_SBB_NAME = HlrSbb.SBB_NAME;
    public static final String UDM_UEAU_SBB_NAME = UdmUeauSbb.SBB_NAME;

    private static final Logger LOG = LogManager.getLogger(HssApp.class);

    private final List<String> wired = new ArrayList<>();

    public HssApp() {}

    /** Install all three faces. */
    public void install(MicroSleeContainer container, HssBootstrap.Services svc, PcrfPort pcrf) {
        install(container, svc, pcrf, EnumSet.allOf(Face.class));
    }

    /**
     * Install the given faces only.
     *
     * @param pcrf Gx/Rx handler for {@link HssSbb}; {@code null} answers Gx/Rx with 3002
     */
    public void install(MicroSleeContainer container, HssBootstrap.Services svc, PcrfPort pcrf,
                        Set<Face> faces) {
        Objects.requireNonNull(container, "container");
        Objects.requireNonNull(svc, "services");
        Objects.requireNonNull(faces, "faces");
        if (faces.contains(Face.DIAMETER)) {
            container.registerSbbType(HssSbb.class, () -> new HssSbb.$Concrete(svc.hss(), svc.cx(), pcrf));
            record("SbbType:" + SBB_NAME);
            container.mapEventToSbb(DiameterRequestEvent.class, SBB_NAME);
            record("EventMapping:DiameterRequestEvent->" + SBB_NAME);
        }
        if (faces.contains(Face.SS7)) {
            container.registerSbbType(HlrSbb.class, () -> new HlrSbb(svc.hlr()));
            record("SbbType:" + HLR_SBB_NAME);
            container.mapEventToSbb(Ss7MapEvent.Service.class, HLR_SBB_NAME);
            record("EventMapping:Ss7MapEvent.Service->" + HLR_SBB_NAME);
            container.mapEventToSbb(Ss7MapEvent.Dialog.class, HLR_SBB_NAME);
            record("EventMapping:Ss7MapEvent.Dialog->" + HLR_SBB_NAME);
        }
        if (faces.contains(Face.SBI)) {
            container.registerSbbType(UdmUeauSbb.class, () -> new UdmUeauSbb(svc.ueau()));
            record("SbbType:" + UDM_UEAU_SBB_NAME);
            container.mapEventToSbb(HttpWebRequestEvent.class, UDM_UEAU_SBB_NAME);
            record("EventMapping:HttpWebRequestEvent->" + UDM_UEAU_SBB_NAME);
        }
        LOG.info("HssApp installed faces={} wired={}", faces, wired);
    }

    public List<String> wired() {
        return List.copyOf(wired);
    }

    private void record(String entry) {
        if (!wired.contains(entry)) {
            wired.add(entry);
        }
    }

    @FunctionalInterface
    interface LinkProbe {
        String statusLine();
    }

    /** First status is always emitted; later polls emit only actual peer-state changes. */
    public static final class StatusMonitor implements AutoCloseable {
        static final long PERIOD_SECONDS = 30;

        private final LinkProbe probe;
        private final Consumer<String> sink;
        private String previous;
        private ScheduledExecutorService scheduler;

        StatusMonitor(LinkProbe probe, Consumer<String> sink) {
            this.probe = Objects.requireNonNull(probe, "probe");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        void start() {
            if (scheduler != null) {
                throw new IllegalStateException("link status monitor already started");
            }
            poll();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "hss-link-status");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleWithFixedDelay(this::pollQuietly, PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
        }

        synchronized void poll() {
            String current = Objects.requireNonNull(probe.statusLine(), "statusLine");
            if (!current.equals(previous)) {
                previous = current;
                sink.accept(current);
            }
        }

        private void pollQuietly() {
            try {
                poll();
            } catch (RuntimeException e) {
                LOG.warn("epc-hss link status probe failed: {}", e.toString());
            }
        }

        @Override
        public void close() {
            ScheduledExecutorService current = scheduler;
            if (current != null) {
                current.shutdownNow();
            }
        }
    }
}
