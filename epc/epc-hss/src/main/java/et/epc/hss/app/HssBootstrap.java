package et.epc.hss.app;

import et.epc.api.PlmnId;
import et.epc.hss.CxService;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.HeapSecretStore;
import et.epc.hss.HssRepository;
import et.epc.hss.HssService;
import et.epc.hss.JdbcHssRepository;
import et.epc.hss.JdbcSecretStore;
import et.epc.hss.MmeAllowlist;
import et.epc.hss.SecretStore;
import java.util.Objects;
import javax.sql.DataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Standalone wiring for the {@code epc-hss} micro-jainslee process. The durable path
 * builds a Postgres {@code epc} datasource, runs Flyway (dedicated
 * {@code classpath:db/migration-hss} so it never collides with {@code epc-host}'s
 * {@code db/migration} set), and wires {@link JdbcHssRepository} + {@link JdbcSecretStore}.
 * The heap path is the R&amp;D/test fallback with no DB connection.
 *
 * <p>Product JDBC is PostgreSQL on the dedicated {@code epc} database only — never
 * Elisa, never H2/Derby/SQLite (mirrors {@code EpcJdbc} so {@code epc-hss} stays
 * independent of {@code epc-host}).</p>
 */
public final class HssBootstrap {

    public static final String MIGRATION_LOCATION = "classpath:db/migration-hss";
    public static final String DEFAULT_KEK_HEX = "000102030405060708090a0b0c0d0e0f";
    public static final String DEFAULT_MME_REALM = "mme.epc.lab";

    private static final Logger LOG = LogManager.getLogger(HssBootstrap.class);

    private HssBootstrap() {}

    /**
     * Wire the durable Postgres path. Does <em>not</em> connect: suitable for tests and
     * for {@link #wire} after {@link #runMigrations(DataSource)}.
     */
    public static Services durable(PlmnId homePlmn, DataSource ds, String kekHex) {
        return durable(homePlmn, ds, kekHex, MmeAllowlist.disabled());
    }

    /**
     * Wire the durable Postgres path. Does <em>not</em> connect: suitable for tests and
     * for {@link #wire} after {@link #runMigrations(DataSource)}.
     */
    public static Services durable(PlmnId homePlmn, DataSource ds, String kekHex,
                                   MmeAllowlist allowlist) {
        Objects.requireNonNull(ds, "epc-hss durable path requires a DataSource");
        String kek = (kekHex == null || kekHex.isBlank()) ? DEFAULT_KEK_HEX : kekHex;
        JdbcHssRepository repo = new JdbcHssRepository(ds);
        JdbcSecretStore secrets = new JdbcSecretStore(ds, kek);
        return services(homePlmn, ds, repo, secrets, allowlist, "postgres");
    }

    /** Heap fallback for R&amp;D / unit tests — no DB opened. */
    public static Services heap(PlmnId homePlmn) {
        return heap(homePlmn, MmeAllowlist.disabled());
    }

    /** Heap fallback with an MME allowlist applied. */
    public static Services heap(PlmnId homePlmn, MmeAllowlist allowlist) {
        return services(homePlmn, null, new HeapHssRepository(), new HeapSecretStore(),
                allowlist, "heap (R&D)");
    }

    /**
     * Choose the path by config. A blank JDBC URL selects the heap fallback; a present
     * URL must be Postgres on the {@code epc} DB, then Flyway migrates it before the
     * durable services are wired.
     */
    public static Services wire(PlmnId homePlmn, String jdbcUrl, String user, String password, String kekHex) {
        return wire(homePlmn, jdbcUrl, user, password, kekHex, MmeAllowlist.disabled());
    }

    /**
     * Choose the path by config. A blank JDBC URL selects the heap fallback; a present
     * URL must be Postgres on the {@code epc} DB, then Flyway migrates it before the
     * durable services are wired. {@code allowlist} gates ULR/AIR peer trust.
     */
    public static Services wire(PlmnId homePlmn, String jdbcUrl, String user, String password,
                                String kekHex, MmeAllowlist allowlist) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            LOG.info("epc-hss bootstrap: no hss.jdbc.url -> heap fallback (R&D mode), "
                    + "mme.allowlist.enabled={}", allowlist.enabled());
            return heap(homePlmn, allowlist);
        }
        requirePostgres(jdbcUrl);
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(jdbcUrl.trim());
        if (user != null && !user.isBlank()) {
            ds.setUser(user);
        }
        if (password != null) {
            ds.setPassword(password);
        }
        runMigrations(ds);
        return durable(homePlmn, ds, kekHex, allowlist);
    }

    /** Apply the standalone epc-hss schemas via Flyway on the {@code epc} datasource. */
    public static void runMigrations(DataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate();
        LOG.info("epc-hss Flyway migrated {}", MIGRATION_LOCATION);
    }

    private static Services services(PlmnId homePlmn, DataSource ds, HssRepository repo,
                                     SecretStore secrets, MmeAllowlist allowlist, String mode) {
        HssService hss = new HssService(repo, secrets, homePlmn,
                allowlist == null ? MmeAllowlist.disabled() : allowlist, DEFAULT_MME_REALM);
        CxService cx = new CxService(repo, secrets);
        boolean durable = repo instanceof JdbcHssRepository;
        return new Services(ds, repo, secrets, hss, cx, durable, mode);
    }

    private static void requirePostgres(String jdbcUrl) {
        String url = jdbcUrl.trim();
        if (url.startsWith("jdbc:h2:") || url.startsWith("jdbc:derby:") || url.startsWith("jdbc:sqlite:")) {
            throw new IllegalStateException("epc-hss JDBC must be PostgreSQL, not " + scheme(url));
        }
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new IllegalStateException("epc-hss JDBC must be PostgreSQL, got " + scheme(url));
        }
        if (url.contains("/elisa")) {
            throw new IllegalStateException("epc-hss JDBC must use the epc database, not Elisa elisa");
        }
    }

    private static String scheme(String url) {
        int colon = url.indexOf(':', 5);
        return colon < 0 ? url : url.substring(0, colon);
    }

    /** The services wired by a bootstrap run. */
    public record Services(DataSource dataSource, HssRepository repo, SecretStore secrets,
                           HssService hss, CxService cx, boolean durable, String mode) {

        public Services {
            Objects.requireNonNull(repo);
            Objects.requireNonNull(secrets);
            Objects.requireNonNull(hss);
            Objects.requireNonNull(cx);
        }

        public boolean durable() {
            return durable;
        }
    }
}