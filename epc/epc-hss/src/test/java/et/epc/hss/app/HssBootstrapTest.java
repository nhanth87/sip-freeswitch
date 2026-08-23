package et.epc.hss.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.core.MicroSleeConfiguration;
import com.microjainslee.core.MicroSleeContainer;
import et.epc.api.PlmnId;
import et.epc.hss.HeapHssRepository;
import et.epc.hss.JdbcHssRepository;
import et.epc.hss.JdbcSecretStore;
import et.epc.hss.sbbs.HssSbb;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * App-level wiring: the standalone {@link HssBootstrap} is the single entry point
 * {@code HssMain} uses. These tests pin that the durable path wires the JDBC repo +
 * secret store (and that the HssApp installs cleanly against it), and that the heap
 * fallback is selected when no JDBC URL is configured. No Postgres is required —
 * {@code PGSimpleDataSource} does not connect, so the durable repo is constructed but
 * never opened here.
 */
class HssBootstrapTest {

    private MicroSleeContainer container;

    @AfterEach
    void tearDown() {
        if (container != null && container.getState() == MicroSleeContainer.State.STARTED) {
            container.stop();
        }
    }

    @Test
    void durablePathWiresJdbcRepoAndSecretStore() {
        PlmnId homePlmn = new PlmnId("001", "01");
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl("jdbc:postgresql://postgres-epc:5432/epc");

        HssBootstrap.Services svc = HssBootstrap.durable(homePlmn, ds, HssBootstrap.DEFAULT_KEK_HEX);

        assertTrue(svc.durable());
        assertEquals("postgres", svc.mode());
        assertInstanceOf(JdbcHssRepository.class, svc.repo());
        assertInstanceOf(JdbcSecretStore.class, svc.secrets());

        // App-level: the SBB install must succeed against the durable services too.
        container = new MicroSleeContainer(MicroSleeConfiguration.builder()
                .eventRouterBufferSize(4096)
                .sbbPoolMin(32)
                .sbbPoolMax(4096)
                .build());
        HssApp app = new HssApp();
        app.install(container, svc.hss(), svc.cx(), null);
        assertTrue(container.getSbbTypeRegistry().isRegistered(HssSbb.class));
        assertTrue(app.wired().contains("SbbType:HssSbb"));
        assertTrue(app.wired().contains("EventMapping:DiameterRequestEvent->HssSbb"));
    }

    @Test
    void heapFallbackWhenNoJdbcUrlConfigured() {
        PlmnId homePlmn = new PlmnId("001", "01");
        HssBootstrap.Services svc = HssBootstrap.wire(homePlmn, null, "epc", "epc", null);

        assertFalse(svc.durable());
        assertEquals("heap (R&D)", svc.mode());
        assertInstanceOf(HeapHssRepository.class, svc.repo());
    }

    @Test
    void rejectsNonPostgresOrElisaDatabase() {
        PlmnId homePlmn = new PlmnId("001", "01");
        assertThrows(IllegalStateException.class,
                () -> HssBootstrap.wire(homePlmn, "jdbc:h2:mem:x", "epc", "epc", null));
        assertThrows(IllegalStateException.class,
                () -> HssBootstrap.wire(homePlmn, "jdbc:derby:mem:x", "epc", "epc", null));
        assertThrows(IllegalStateException.class,
                () -> HssBootstrap.wire(homePlmn, "jdbc:postgresql://localhost/elisa", "epc", "epc", null));
    }

    @Test
    void flywayTargetsDedicatedHssMigrationLocation() {
        assertEquals("classpath:db/migration-hss", HssBootstrap.MIGRATION_LOCATION);
    }
}