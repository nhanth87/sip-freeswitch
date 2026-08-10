package et.elisa.ims.charging;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Phase B Rf ACR stub — logs Diameter Rf ACR STOP keyed by icid; no wire yet.
 */
@ApplicationScoped
public class LoggingRfAcrEmitter implements RfAcrEmitter {

    private static final Logger LOG = LogManager.getLogger(LoggingRfAcrEmitter.class);

    @Inject CgratesConfig config;

    public LoggingRfAcrEmitter() {}

    public LoggingRfAcrEmitter(CgratesConfig config) {
        this.config = config;
    }

    @Override
    public void emitStop(RfAcrRequest request) {
        try {
            if (config == null || !config.rfDualWriteActive()) {
                return;
            }
            if (request == null) {
                return;
            }
            String icid = request.icid();
            if (icid == null || icid.isBlank()) {
                LOG.debug("Rf ACR STOP stub skipped — no icid sipCallId={}", request.sipCallId());
                return;
            }
            LOG.info("Rf ACR STOP stub icid={} sipCallId={} account={} duration={} cause={}",
                    icid, request.sipCallId(), request.account(),
                    request.durationSec(), request.hangupCause());
        } catch (Throwable t) {
            LOG.warn("Rf ACR stub soft-fail: {}", t.toString());
        }
    }
}
