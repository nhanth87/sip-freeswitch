package et.epc.pcrf.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;

/**
 * Gx IP-CAN session request event raised by {@code diameter-ra} for the
 * {@link et.epc.pcrf.sbbs.PcrfSbb} application. The Diameter demux still keys on
 * {@code DiameterRequestEvent}; this typed event carries the parsed CCR fields
 * for business logic and tracing.
 */
@EventType(name = "epc.pcrf.gx", vendor = "et.epc", version = "1.0")
public final class GxRequestEvent implements SleeEvent {

    private final String imsi;
    private final String apn;
    private final long chargingId;
    private final String requestType;

    public GxRequestEvent(String imsi, String apn, long chargingId, String requestType) {
        this.imsi = imsi;
        this.apn = apn;
        this.chargingId = chargingId;
        this.requestType = requestType;
    }

    public String imsi() {
        return imsi;
    }

    public String apn() {
        return apn;
    }

    public long chargingId() {
        return chargingId;
    }

    public String requestType() {
        return requestType;
    }
}