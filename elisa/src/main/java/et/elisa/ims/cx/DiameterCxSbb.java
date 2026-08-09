package et.elisa.ims.cx;

import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.api.annotations.InjectRa;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;

/** Bridges Diameter Cx events to CxServer / CxClient. */
public final class DiameterCxSbb implements Sbb, SleeEventHandler {

    private final CxServer server;
    private final CxClient client;

    @InjectRa(name = "diameter-ra")
    private volatile RaCommandPort diameterRa;

    public DiameterCxSbb() {
        this(null, null);
    }

    public DiameterCxSbb(CxServer server, CxClient client) {
        this.server = server;
        this.client = client;
    }

    @Override public void sbbCreate() {
        if (diameterRa != null) {
            if (server != null) {
                server.bindDiameter(diameterRa);
            }
            if (client != null) {
                client.bindDiameter(diameterRa);
            }
        }
    }

    @Override public void sbbActivate() {}
    @Override public void sbbPassivate() {}
    @Override public void sbbRemove() {}

    @Override
    public void onEvent(SleeEvent event, ActivityContextInterface aci) {
        if (diameterRa != null) {
            if (server != null) {
                server.bindDiameter(diameterRa);
            }
            if (client != null) {
                client.bindDiameter(diameterRa);
            }
        }
        switch (event) {
            case DiameterRequestEvent e -> {
                if (server != null) {
                    server.onRequest(e);
                }
            }
            case DiameterAnswerEvent e -> {
                if (client != null) {
                    client.onAnswer(e.sessionId(), e.avps());
                }
            }
            default -> {}
        }
    }
}
