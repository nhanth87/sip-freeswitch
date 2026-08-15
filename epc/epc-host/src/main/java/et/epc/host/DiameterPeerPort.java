package et.epc.host;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import et.epc.api.link.PeerLink;
import java.util.Objects;

/**
 * Thin wrap of vendor {@code diameter-ra}. {@link #live()} is {@code isPeerReady()}
 * (CER/CEA + watchdog) — never local listen.
 */
public final class DiameterPeerPort {

    private final DiameterRaEndpoint endpoint;

    public DiameterPeerPort(DiameterRaEndpoint endpoint) {
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    public void send(OutboundCommand command) {
        endpoint.sendCommand(command);
    }

    public boolean live() {
        return endpoint.isPeerReady();
    }

    public boolean connected() {
        return endpoint.isPeerConnected();
    }

    public PeerLink snapshot(String name) {
        if (endpoint.isPeerReady()) {
            return PeerLink.up(name, "cea+watchdog isPeerReady=true");
        }
        return PeerLink.down(name, true, endpoint.isPeerConnected()
                ? "tcp-up-awaiting-cea" : "listen-not-cea");
    }

    public DiameterRaEndpoint endpoint() {
        return endpoint;
    }
}
