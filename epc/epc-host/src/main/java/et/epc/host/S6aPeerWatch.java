package et.epc.host;

import et.epc.api.link.PeerLink;

/**
 * S6a live = CER/CEA + watchdog, never Diameter listen bind.
 */
public final class S6aPeerWatch {
    private volatile PeerLink link = PeerLink.down("s6a", false, "no-cea");
    private volatile boolean listening;

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
        return link;
    }

    public void refreshFrom(DiameterPeerPort diameter) {
        PeerLink p = diameter.snapshot("s6a");
        this.listening = p.localListen();
        this.link = p;
    }
}
