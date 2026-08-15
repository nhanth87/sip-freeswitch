package et.epc.host;

import et.epc.api.link.PeerLink;
import com.microjainslee.ra.gtpv2c.Gtpv2RaEndpoint;
import com.microjainslee.ra.pfcp.PfcpRaEndpoint;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/** Admin/health must compose these primitives — never LISTEN as UP. */
public final class LinkStatusBoard {

    private final S6aPeerWatch s6a;
    private final Gtpv2RaEndpoint gtp;
    private final PfcpRaEndpoint pfcp;
    private final InetSocketAddress gtpPeer;
    private final InetSocketAddress pfcpPeer;

    public LinkStatusBoard(
            S6aPeerWatch s6a,
            Gtpv2RaEndpoint gtp,
            PfcpRaEndpoint pfcp,
            InetSocketAddress gtpPeer,
            InetSocketAddress pfcpPeer) {
        this.s6a = s6a;
        this.gtp = gtp;
        this.pfcp = pfcp;
        this.gtpPeer = gtpPeer;
        this.pfcpPeer = pfcpPeer;
    }

    public Map<String, PeerLink> snapshot() {
        Map<String, PeerLink> m = new LinkedHashMap<>();
        if (s6a != null) {
            m.put("s6a", s6a.snapshot());
        }
        if (gtp != null && gtpPeer != null) {
            m.put("gtp", toLink("gtp", gtp.peer(gtpPeer)));
        }
        if (pfcp != null && pfcpPeer != null) {
            m.put("pfcp", toLink("pfcp", pfcp.peer(pfcpPeer)));
        }
        return Map.copyOf(m);
    }

    private static PeerLink toLink(String name, com.microjainslee.ra.gtpv2c.PeerState s) {
        if (s.live()) {
            return PeerLink.up(name, s.evidence());
        }
        return PeerLink.down(name, s.localListen(), s.evidence());
    }

    private static PeerLink toLink(String name, com.microjainslee.ra.pfcp.PeerState s) {
        if (s.live()) {
            return PeerLink.up(name, s.evidence());
        }
        return PeerLink.down(name, s.localListen(), s.evidence());
    }

    public boolean anyLive() {
        return snapshot().values().stream().anyMatch(PeerLink::live);
    }
}
