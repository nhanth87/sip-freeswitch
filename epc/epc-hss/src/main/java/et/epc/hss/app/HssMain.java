package et.epc.hss.app;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.PlmnId;
import et.epc.hss.CxService;
import et.epc.hss.HssService;
import et.epc.hss.MmeAllowlist;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Standalone embedded mode: a lab HSS/AuC + Cx service wired into one micro-jainslee
 * container. The boot path is config-driven through {@link HssBootstrap}:
 * <ul>
 *   <li>durable (Postgres {@code epc} DB + Flyway {@code db/migration-hss} +
 *       {@code JdbcHssRepository} / {@code JdbcSecretStore}) when {@code hss.jdbc.url}
 *       is set;</li>
 *   <li>heap fallback (no DB) otherwise — R&amp;D mode.</li>
 * </ul>
 * No transport is bound here; the vendor diameter RA is attached by the host process
 * when peer routing is available.
 */
public final class HssMain {

    private static final Logger LOG = LogManager.getLogger(HssMain.class);

    private HssMain() {}

    public static void main(String[] args) throws Exception {
        MicroSleeConfiguration cfg = MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build();

        MicroSleeContainer container = new MicroSleeContainer(cfg);

        String jdbcUrl = System.getProperty("hss.jdbc.url", "");
        String user = System.getProperty("hss.jdbc.user", "epc");
        String password = System.getProperty("hss.jdbc.password", "epc");
        String kekHex = System.getProperty("hss.secret.kek-hex", HssBootstrap.DEFAULT_KEK_HEX);
        // MME peer-trust drawbridge (Open5GS #4337): comma list of origin hosts/realms/PLMNs.
        boolean allowlistEnabled = Boolean.parseBoolean(
                System.getProperty("epc.hss.mme.allowlist.enabled", "false"));
        String allowlistRaw = System.getProperty("epc.hss.mme.allowlist", "");
        MmeAllowlist allowlist = MmeAllowlist.parse(allowlistEnabled, allowlistRaw);
        PlmnId homePlmn = new PlmnId("001", "01");

        HssBootstrap.Services svc = HssBootstrap.wire(
                homePlmn, jdbcUrl.isBlank() ? null : jdbcUrl, user, password, kekHex, allowlist);

        HssService hss = svc.hss();
        CxService cx = svc.cx();
        HssApp app = new HssApp();
        app.install(container, hss, cx, null);

        container.start();
        Runtime.getRuntime().addShutdownHook(new Thread(container::stop, "hss-shutdown"));

        LOG.info("epc-hss main started wired={} mode={} durable={} (standalone embedded, "
                        + "no Diameter transport)",
                app.wired(), svc.mode(), svc.durable());
        Thread.currentThread().join();
    }
}