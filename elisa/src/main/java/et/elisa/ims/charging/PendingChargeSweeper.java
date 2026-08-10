package et.elisa.ims.charging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Phase C stub — SessionS reconcile sweeper. No-op until {@code sf.ocs.session.enabled=true}
 * and a real CGRates SessionS client lands.
 */
@ApplicationScoped
public class PendingChargeSweeper {

    private static final Logger LOG = LogManager.getLogger(PendingChargeSweeper.class);

    @Inject CgratesConfig config;
    @Inject PendingChargeStore store;

    public PendingChargeSweeper() {}

    public PendingChargeSweeper(CgratesConfig config, PendingChargeStore store) {
        this.config = config;
        this.store = store;
    }

    /**
     * Sweep pending debits once. Stub: logs count only — no SessionS debit yet (follow-up PR).
     *
     * @return number of pending rows inspected
     */
    public int reconcileOnce() {
        if (config == null || !config.ocsSessionEnabled()) {
            return 0;
        }
        if (store == null) {
            return 0;
        }
        var pending = store.listPending(100);
        if (!pending.isEmpty()) {
            LOG.info("PendingChargeSweeper stub inspected={} (SessionS debit deferred)", pending.size());
        }
        return pending.size();
    }
}
