package et.epc.hss.app;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.PlmnId;
import et.epc.hss.MmeAllowlist;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone HSS process: one micro-jainslee container, one AuC, up to three links
 * ({@link HssLinks}) — Diameter S6a/Cx ({@code HssSbb}), SS7 MAP SAI over SCTP ({@code HlrSbb}) and
 * HTTP/2 Nudm-UEAU ({@code UdmUeauSbb}). The datastore is chosen by {@link HssBootstrap}:
 * <ul>
 *   <li>durable (Postgres {@code epc} DB + Flyway {@code db/migration-hss} +
 *       {@code JdbcHssRepository} / {@code JdbcSecretStore}) when {@code hss.jdbc.url} is set;</li>
 *   <li>heap fallback (no DB) otherwise — R&amp;D mode.</li>
 * </ul>
 *
 * <p>Start-up order: config + KEK check (refuses the public default KEK unless
 * {@code hss.lab=true}) → services → SBB faces for the enabled links → RAs → {@code start()} →
 * {@link HssLinks.Bound#verify()}. An enabled link that does not come up stops the process; the
 * startup log lists each link's RA truth ({@code bound} / {@code active} / {@code listening}, plus
 * peer readiness — which is {@code false} until a peer has answered).
 *
 * <p>Note: the durable repository runs JDBC on the SBB event thread (known, documented debt).
 */
public final class HssMain {

    private static final Logger LOG = LogManager.getLogger(HssMain.class);

    /** Exit status when configuration or a link refuses the start. */
    public static final int EXIT_REFUSED = 2;

    private HssMain() {}

    public static void main(String[] args) throws InterruptedException {
        Running running;
        try {
            running = boot(HssLinks.Lookup.system());
        } catch (IllegalArgumentException | IllegalStateException e) {
            LOG.fatal("epc-hss refused to start: {}", e.getMessage());
            LogManager.shutdown();
            System.exit(EXIT_REFUSED);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(running::stop, "hss-shutdown"));
        Thread.currentThread().join();
    }

    /**
     * Build and start the HSS from {@code lookup}. Throws {@link IllegalArgumentException} for bad
     * configuration and {@link IllegalStateException} for a refused KEK or a link that failed; the
     * container is stopped before a link failure propagates.
     */
    public static Running boot(HssLinks.Lookup lookup) {
        HssLinks.Config links = HssLinks.load(lookup);
        String kek = links.kek();
        warnIfOnlyLoopbackDiameterPeer(links);

        String jdbcUrl = lookup.get("hss.jdbc.url");
        String user = orDefault(lookup.get("hss.jdbc.user"), "epc");
        String password = orDefault(lookup.get("hss.jdbc.password"), "epc");
        // MME peer-trust drawbridge (Open5GS #4337): comma list of origin hosts/realms/PLMNs.
        boolean allowlistEnabled = Boolean.parseBoolean(
                orDefault(lookup.get("epc.hss.mme.allowlist.enabled"), "false"));
        MmeAllowlist allowlist = MmeAllowlist.parse(allowlistEnabled,
                orDefault(lookup.get("epc.hss.mme.allowlist"), ""));
        PlmnId homePlmn = links.homePlmn();

        HssBootstrap.Services svc = HssBootstrap.wire(homePlmn,
                jdbcUrl == null || jdbcUrl.isBlank() ? null : jdbcUrl, user, password, kek, allowlist,
                links.sbi().allowedServingPlmns());

        MicroSleeContainer container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build());
        HssApp app = new HssApp();
        app.install(container, svc, null, links.faces());
        HssLinks.Bound bound = HssLinks.bind(container, links);

        container.start();
        try {
            bound.verify();
        } catch (RuntimeException e) {
            container.stop();
            throw e;
        }
        if (links.faces().isEmpty()) {
            LOG.warn("epc-hss started with every link off — set {} / {} / {}=true",
                    HssLinks.DIAMETER_ENABLED, HssLinks.SS7_ENABLED, HssLinks.SBI_ENABLED);
        }
        LOG.info("epc-hss started mode={} durable={} lab={} homePlmn={}{} wired={}",
                svc.mode(), svc.durable(), links.lab(), homePlmn.mcc(), homePlmn.mnc(), app.wired());
        HssApp.StatusMonitor status = new HssApp.StatusMonitor(bound::statusLine,
                line -> LOG.info("epc-hss status {}", line));
        try {
            status.start();
        } catch (RuntimeException e) {
            status.close();
            container.stop();
            throw e;
        }
        return new Running(container, app, bound, svc, status);
    }

    static void warnIfOnlyLoopbackDiameterPeer(HssLinks.Config links) {
        HssLinks.Diameter diameter = links.diameter();
        if (!diameter.enabled() || !diameter.peers().isEmpty() || isLoopback(diameter.host())) {
            return;
        }
        LOG.warn("epc-hss has only a loopback Diameter server peer: {} is empty while {}={} is non-loopback; "
                        + "remote MME/S-CSCF peers cannot connect — configure {}",
                HssLinks.DIAMETER_PEERS, HssLinks.DIAMETER_HOST, diameter.host(), HssLinks.DIAMETER_PEERS);
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false; // a non-loopback/unresolved bind still gets the conservative warning
        }
    }

    private static String orDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    /** A started HSS. */
    public record Running(MicroSleeContainer container, HssApp app, HssLinks.Bound links,
                          HssBootstrap.Services services, HssApp.StatusMonitor statusMonitor) {

        public Running {
            Objects.requireNonNull(container);
            Objects.requireNonNull(app);
            Objects.requireNonNull(links);
            Objects.requireNonNull(services);
            Objects.requireNonNull(statusMonitor);
        }

        public void stop() {
            statusMonitor.close();
            if (container.getState() == MicroSleeContainer.State.STARTED) {
                container.stop();
            }
        }
    }
}
