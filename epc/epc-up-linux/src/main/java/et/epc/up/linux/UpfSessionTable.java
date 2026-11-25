package et.epc.up.linux;

import com.microjainslee.ra.pfcp.PfcpSessionState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PFCP sessions of this UP function (TS 29.244 §5.2), keyed by the CP SEID: rule state and the
 * URRs installed per session. A plain service shared by every {@code UpfPfcpSbb} entity — the
 * Session Modification / Deletion of a session can be delivered to a different pooled entity
 * than its Establishment, so the state must not live in an SBB instance field.
 */
public final class UpfSessionTable {

    private final Map<Long, PfcpSessionState> states = new ConcurrentHashMap<>();
    private final Map<Long, List<String>> urrs = new ConcurrentHashMap<>();

    public PfcpSessionState get(long seid) {
        return states.get(seid);
    }

    public void put(long seid, PfcpSessionState state) {
        states.put(seid, state);
    }

    public PfcpSessionState remove(long seid) {
        urrs.remove(seid);
        return states.remove(seid);
    }

    public List<String> urrs(long seid) {
        return urrs.getOrDefault(seid, List.of());
    }

    public void urrs(long seid, List<String> ids) {
        if (ids.isEmpty()) {
            urrs.remove(seid);
        } else {
            urrs.put(seid, List.copyOf(ids));
        }
    }

    public int size() {
        return states.size();
    }
}
