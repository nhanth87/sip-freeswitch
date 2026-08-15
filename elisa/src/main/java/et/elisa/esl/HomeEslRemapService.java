package et.elisa.esl;

import et.elisa.cluster.FsPairRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * R9: on S-CSCF failover, bind ESL to the dialog's <em>home</em> FreeSWITCH
 * (checkpoint {@code fsNodeId} + {@code sf_fs_pair}). Media stays on that
 * node; this does not move RTP. {@link EslFailoverService} still walks only
 * <em>this</em> node's pair list when primary is dead — a pin here stops that
 * probe from stealing another S-CSCF's FS while recovered dialogs need home.
 */
@ApplicationScoped
public class HomeEslRemapService {

    private static final Logger LOG = Logger.getLogger(HomeEslRemapService.class);

    @Inject FsPairRegistry pairs;
    @Inject FreeSwitchRaBridge bridge;

    /** When set, {@link EslFailoverService} retries this FS only. */
    private volatile String pinnedHomeFsNodeId;

    @Inject
    public HomeEslRemapService() {
    }

    /** Unit-test wiring. */
    public HomeEslRemapService(FsPairRegistry pairs, FreeSwitchRaBridge bridge) {
        this.pairs = pairs;
        this.bridge = bridge;
    }

    public String pinnedHomeFsNodeId() {
        return pinnedHomeFsNodeId;
    }

    public enum Outcome {
        SKIPPED_NO_HOME,
        SKIPPED_UNKNOWN_FS,
        ALREADY_ON_HOME,
        REBOUND,
        HOME_DOWN
    }

    public Outcome remapToHomeFs(String homeFsNodeId) {
        return remapToHomeFs(null, homeFsNodeId);
    }

    /**
     * Bind ESL to {@code homeFsNodeId} when this node's primary is a different FS.
     * Home ESL down → {@code eslLive=false}; do not rebind to another FS.
     */
    public Outcome remapToHomeFs(String homeElisaNodeId, String homeFsNodeId) {
        if (homeFsNodeId == null || homeFsNodeId.isBlank()) {
            return Outcome.SKIPPED_NO_HOME;
        }
        if (bridge == null || pairs == null) {
            return Outcome.SKIPPED_NO_HOME;
        }
        Optional<FsPairRegistry.EslTarget> home = pairs.targetForHome(homeElisaNodeId, homeFsNodeId);
        if (home.isEmpty()) {
            LOG.warnf("R9 home FS unknown fsNode=%s — not rebinding", homeFsNodeId);
            return Outcome.SKIPPED_UNKNOWN_FS;
        }
        FsPairRegistry.EslTarget target = home.get();
        boolean primaryIsHome = pairs.primary()
                .map(p -> homeFsNodeId.equals(p.fsNodeId()))
                .orElse(false);
        if (primaryIsHome) {
            pinnedHomeFsNodeId = null;
            if (homeFsNodeId.equals(bridge.activeFsNodeId()) && !bridge.live()) {
                LOG.warnf("R9 home FS ESL down fsNode=%s eslLive=false — media stays home",
                        homeFsNodeId);
                return Outcome.HOME_DOWN;
            }
            return Outcome.ALREADY_ON_HOME;
        }
        pinnedHomeFsNodeId = homeFsNodeId;
        if (homeFsNodeId.equals(bridge.activeFsNodeId())) {
            if (!bridge.live() && !bridge.probeLive()) {
                LOG.warnf("R9 home FS ESL down fsNode=%s eslLive=false — not moving RTP",
                        homeFsNodeId);
                return Outcome.HOME_DOWN;
            }
            return Outcome.ALREADY_ON_HOME;
        }
        LOG.infof("R9 ESL remap to home FS fsNode=%s %s:%d",
                target.fsNodeId(), target.host(), target.port());
        try {
            bridge.rebind(target);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "R9 ESL remap failed fsNode=%s eslLive=false", homeFsNodeId);
            return Outcome.HOME_DOWN;
        }
        if (!bridge.live()) {
            LOG.warnf("R9 home FS ESL down fsNode=%s %s:%d eslLive=false — not moving RTP",
                    target.fsNodeId(), target.host(), target.port());
            return Outcome.HOME_DOWN;
        }
        return Outcome.REBOUND;
    }
}
