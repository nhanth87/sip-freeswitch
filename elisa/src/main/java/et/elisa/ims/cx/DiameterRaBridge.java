package et.elisa.ims.cx;

import com.microjainslee.ra.diameter.DiameterRaEndpoint;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DiameterRaBridge {
    private volatile DiameterRaEndpoint endpoint;

    public void bind(DiameterRaEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    public boolean peerReady() {
        DiameterRaEndpoint e = endpoint;
        return e != null && e.isPeerReady();
    }

    public boolean peerConnected() {
        DiameterRaEndpoint e = endpoint;
        return e != null && e.isPeerConnected();
    }

    public boolean bound() {
        return endpoint != null;
    }
}
