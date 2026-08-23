package et.epc.hss.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import java.util.Map;
import java.util.Objects;

/**
 * Typed re-fire vehicle for the HSS Diameter application gateway. The primary
 * input is the vendor {@link DiameterRequestEvent}; this event lets a test or a
 * fan-in path re-publish a normalized Diameter request without holding a
 * transport event, and can be converted back to a {@link DiameterRequestEvent}
 * via {@link #toDiameterRequest()}.
 */
@EventType(name = "epc.hss.diameter", vendor = "et.epc", version = "1.0")
public final class HssDiameterEvent implements SleeEvent {

    private final long applicationId;
    private final long commandCode;
    private final String sessionId;
    private final Map<Integer, String> avps;

    public HssDiameterEvent(long applicationId, long commandCode, String sessionId,
            Map<Integer, String> avps) {
        Objects.requireNonNull(sessionId, "sessionId");
        this.applicationId = applicationId;
        this.commandCode = commandCode;
        this.sessionId = sessionId;
        this.avps = Map.copyOf(avps == null ? Map.of() : avps);
    }

    public long applicationId() {
        return applicationId;
    }

    public long commandCode() {
        return commandCode;
    }

    public String sessionId() {
        return sessionId;
    }

    public Map<Integer, String> avps() {
        return avps;
    }

    /** Rebuild the vendor request event (transport ids are 0 on a typed re-fire). */
    public DiameterRequestEvent toDiameterRequest() {
        return new DiameterRequestEvent(sessionId, applicationId, (int) commandCode,
                0L, 0L, null, null, null, null, avps);
    }
}