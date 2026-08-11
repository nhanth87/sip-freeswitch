package et.elisa.cluster;

import et.elisa.recovery.ClusterView;
import et.elisa.recovery.SbbRecoveryManagerImpl;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.infinispan.remoting.transport.Address;
import org.jboss.logging.Logger;

/**
 * DIST_SYNC membership → {@link SbbRecoveryManagerImpl#onClusterViewChange}.
 * One JGroups view (same manager as dialog/owner caches).
 * Listener is a plain object (not a CDI proxy) so Infinispan sees {@link ViewChanged}.
 */
@ApplicationScoped
public class ElisaClusterViewBridge {

    private static final Logger LOG = Logger.getLogger(ElisaClusterViewBridge.class);

    @Inject ElisaInfinispanManager cluster;
    @Inject SbbRecoveryManagerImpl recovery;

    private volatile ViewListener listener;

    public void attach() {
        if (listener != null || cluster == null || !cluster.enabled() || !cluster.distSync()) {
            return;
        }
        EmbeddedCacheManager manager = cluster.manager();
        if (manager == null) {
            return;
        }
        listener = new ViewListener(cluster, recovery);
        manager.addListener(listener);
        LOG.infof("ElisaClusterViewBridge attached cluster=%s node=%s",
                cluster.clusterName(), cluster.nodeId());
    }

    @PreDestroy
    void detach() {
        ViewListener l = listener;
        if (l == null || cluster == null || cluster.manager() == null) {
            return;
        }
        try {
            cluster.manager().removeListener(l);
        } catch (RuntimeException ex) {
            LOG.debugf(ex, "remove view listener");
        }
        listener = null;
    }

    static Set<String> toNodeIds(List<Address> members) {
        Set<String> ids = new HashSet<>();
        if (members == null) {
            return ids;
        }
        for (Address addr : members) {
            if (addr == null) {
                continue;
            }
            String raw = String.valueOf(addr);
            if (!raw.isBlank()) {
                ids.add(logicalNodeId(raw));
            }
        }
        return ids;
    }

    /** Keep {@code sf.cluster.node-id}; drop JGroups {@code name/ip:port} suffix. */
    static String logicalNodeId(String address) {
        if (address == null) {
            return "";
        }
        String s = address.trim();
        int slash = s.indexOf('/');
        if (slash > 0) {
            return s.substring(0, slash);
        }
        return s;
    }

    @Listener(sync = false)
    static final class ViewListener {
        private final ElisaInfinispanManager cluster;
        private final SbbRecoveryManagerImpl recovery;

        ViewListener(ElisaInfinispanManager cluster, SbbRecoveryManagerImpl recovery) {
            this.cluster = cluster;
            this.recovery = recovery;
        }

        @ViewChanged
        public void onViewChanged(ViewChangedEvent event) {
            if (event == null || recovery == null) {
                return;
            }
            Set<String> oldIds = toNodeIds(event.getOldMembers());
            Set<String> newIds = toNodeIds(event.getNewMembers());
            List<String> departed = new ArrayList<>();
            for (String id : oldIds) {
                if (!newIds.contains(id)) {
                    departed.add(id);
                }
            }
            String local = cluster.nodeId();
            boolean primary = !newIds.isEmpty();
            ClusterView view = new ClusterView(local, newIds, departed, primary);
            if (!departed.isEmpty()) {
                LOG.infof("ISPN view change local=%s departed=%s members=%s", local, departed, newIds);
            }
            recovery.onClusterViewChange(view);
        }
    }
}
