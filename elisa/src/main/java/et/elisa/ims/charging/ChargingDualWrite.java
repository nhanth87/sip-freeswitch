package et.elisa.ims.charging;

import et.elisa.persist.SfCdr;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Offline dual-write after SfCdr persist: CDRsV1 + Rf ACR stub behind {@code sf.charging.dual-write}.
 */
@ApplicationScoped
public class ChargingDualWrite {

    private static final Logger LOG = LogManager.getLogger(ChargingDualWrite.class);

    @Inject CgratesConfig config;
    @Inject CgratesClient cgrates;
    @Inject ChargingAccountResolver accounts;
    @Inject RfAcrEmitter rf;

    public ChargingDualWrite() {}

    public ChargingDualWrite(CgratesConfig config, CgratesClient cgrates,
                             ChargingAccountResolver accounts, RfAcrEmitter rf) {
        this.config = config;
        this.cgrates = cgrates;
        this.accounts = accounts;
        this.rf = rf;
    }

    /** Soft-fail wrapper — never rethrows. */
    public void afterCdrPersist(SfCdr row) {
        try {
            if (config == null || !config.dualWrite()) {
                return;
            }
            String account = accounts == null ? null : accounts.resolveAccount(row);
            if (config.cdrsDualWriteActive() && cgrates != null) {
                CgratesCdrEvent event = toEvent(row, account);
                CgratesClient.Result r = cgrates.processCdr(event);
                if (r.attempted() && !r.ok()) {
                    LOG.debug("CDRsV1 dual-write soft: {}", r.detail());
                }
            }
            if (config.rfDualWriteActive() && rf != null) {
                rf.emitStop(RfAcrRequest.stop(
                        row == null ? null : row.icid,
                        row == null ? null : row.sipCallId,
                        account,
                        row == null ? null : row.duration,
                        row == null ? null : row.hangupCause));
            }
        } catch (Throwable t) {
            LOG.warn("charging dual-write soft-fail: {}", t.toString());
        }
    }

    static CgratesCdrEvent toEvent(SfCdr row, String account) {
        String dest = row == null ? null : row.recipient;
        return new CgratesCdrEvent(
                null,
                account,
                dest,
                "call",
                "elisa",
                "*pseudoprepaid",
                CgratesCdrEvent.usageSeconds(row == null ? null : row.duration),
                row == null ? null : row.startTime,
                row == null ? null : row.startTime,
                row == null ? null : row.icid,
                row == null ? null : row.sipCallId,
                row == null ? null : row.callUuid,
                row == null ? null : row.hangupCause);
    }
}
