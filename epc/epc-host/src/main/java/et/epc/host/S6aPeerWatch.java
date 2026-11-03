package et.epc.host;

import et.epc.api.link.PeerLink;

/**
 * S6a live = CER/CEA + watchdog, never Diameter listen bind.
 */
public final class S6aPeerWatch {
    private volatile PeerLink link = PeerLink.down("s6a", false, "no-cea");
    private volatile boolean listening;
    /** Live Diameter port: the peer reaches CER/CEA after bind and the stack answers DWR itself. */
    private volatile DiameterPeerPort source;

    /** Report the bound Diameter port's live state from now on (not a bind-time snapshot). */
    public void track(DiameterPeerPort diameter) {
        this.source = diameter;
    }

    public void bound() {
        listening = true;
        if (!link.live()) {
            link = PeerLink.down("s6a", true, "listen-not-cea");
        }
    }

    public void ceaAccepted(String originHost) {
        link = PeerLink.up("s6a", "cea origin-host=" + originHost);
    }

    public void watchdogTimeout() {
        link = PeerLink.down("s6a", listening, "dwr-timeout");
    }

    public PeerLink snapshot() {
        DiameterPeerPort d = source;
        return d != null ? d.snapshot("s6a") : link;
    }

    public void refreshFrom(DiameterPeerPort diameter) {
        PeerLink p = diameter.snapshot("s6a");
        this.listening = p.localListen();
        this.link = p;
    }
}
