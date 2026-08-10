package et.elisa.ims.charging;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Rf ACR structured-log fallback when Diameter peer not ready or RA unbound.
 * Not a CDI bean — used by {@link DiameterRfAcrEmitter}.
 */
public final class LoggingRfAcrEmitter implements RfAcrEmitter {

    private static final Logger LOG = LogManager.getLogger(LoggingRfAcrEmitter.class);

    private final CgratesConfig config;

    public LoggingRfAcrEmitter(CgratesConfig config) {
        this.config = config;
    }

    @Override
    public void emitStart(RfAcrRequest request) {
        log("START", request);
    }

    @Override
    public void emitStop(RfAcrRequest request) {
        log("STOP", request);
    }

    private void log(String kind, RfAcrRequest request) {
        try {
            if (config == null || !config.rfDualWriteActive()) {
                return;
            }
            if (request == null) {
                return;
            }
            String icid = request.icid();
            if (icid == null || icid.isBlank()) {
                LOG.debug("Rf ACR {} log-fallback skipped — no icid sipCallId={}", kind, request.sipCallId());
                return;
            }
            LOG.info("Rf ACR {} log-fallback icid={} sipCallId={} account={} duration={} cause={} avps={}",
                    kind, icid, request.sipCallId(), request.account(),
                    request.durationSec(), request.hangupCause(), RfCodec.acrAvps(request));
        } catch (Throwable t) {
            LOG.warn("Rf ACR log-fallback soft-fail: {}", t.toString());
        }
    }
}
