package et.elisa.ims.charging;

import com.microjainslee.api.RaCommandPort;
import com.microjainslee.ra.diameter.command.SendDiameterRequest;
import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.cx.DiameterRaBridge;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lab Rf ACR: send Accounting-Request via {@code ra-diameter} when {@code peerReady};
 * otherwise {@link LoggingRfAcrEmitter} fallback. Soft-fail always.
 */
@ApplicationScoped
public class DiameterRfAcrEmitter implements RfAcrEmitter {

    private static final Logger LOG = LogManager.getLogger(DiameterRfAcrEmitter.class);

    @Inject CgratesConfig config;
    @Inject DiameterRaBridge diameter;
    @Inject ImsRoleFlags roles;

    private volatile RaCommandPort diameterRa;

    public DiameterRfAcrEmitter() {}

    public DiameterRfAcrEmitter(CgratesConfig config, DiameterRaBridge diameter, ImsRoleFlags roles) {
        this.config = config;
        this.diameter = diameter;
        this.roles = roles;
    }

    /** Bound from {@link et.elisa.bootstrap.ElisaBootstrap} alongside Cx. */
    public void bindDiameter(RaCommandPort port) {
        this.diameterRa = port;
    }

    @Override
    public void emitStart(RfAcrRequest request) {
        if (request == null) {
            return;
        }
        emit("START".equalsIgnoreCase(request.recordType())
                ? request
                : RfAcrRequest.start(request.icid(), request.sipCallId(), request.account()));
    }

    @Override
    public void emitStop(RfAcrRequest request) {
        emit(request);
    }

    private void emit(RfAcrRequest request) {
        try {
            if (!active()) {
                return;
            }
            if (request == null || request.icid() == null || request.icid().isBlank()) {
                LOG.debug("Rf ACR skipped — no icid");
                return;
            }
            if (diameter != null && diameter.peerReady() && diameterRa != null) {
                Map<Integer, String> avps = RfCodec.acrAvps(request);
                String sid = RfCodec.sessionIdFor(request);
                diameterRa.sendCommand(new SendDiameterRequest(
                        sid,
                        RfCodec.RF_APP_ID,
                        RfCodec.ACR,
                        config.rfDestinationHost(),
                        config.rfDestinationRealm(),
                        avps));
                LOG.debug("Rf ACR wire {} icid={} sipCallId={} peerReady=true",
                        request.recordType(), request.icid(), request.sipCallId());
                return;
            }
            LoggingRfAcrEmitter log = new LoggingRfAcrEmitter(config);
            if ("START".equalsIgnoreCase(request.recordType())) {
                log.emitStart(request);
            } else {
                log.emitStop(request);
            }
            if (diameter != null && !diameter.peerReady()) {
                LOG.debug("Rf ACR soft-fallback peerReady=false bound={} icid={}",
                        diameter.bound(), request.icid());
            }
        } catch (Throwable t) {
            LOG.warn("Rf ACR soft-fail icid={}: {}", request == null ? null : request.icid(), t.toString());
        }
    }

    private boolean active() {
        if (roles != null && !roles.featureCharging()) {
            return false;
        }
        return config != null && config.rfDualWriteActive();
    }
}
