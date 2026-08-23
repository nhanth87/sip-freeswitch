package et.epc.sgwc.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;
import java.util.Objects;

/**
 * SGW-C session lifecycle event. Emitted by the SBB after an S11 procedure for
 * observability / downstream correlations. Immutable.
 */
@EventType(name = "epc.sgwc.session", vendor = "et.epc", version = "1.0")
public final class SgwSessionEvent implements SleeEvent {

    public enum Kind {
        CREATE,
        MODIFY,
        RELEASE,
        DELETE
    }

    private final String imsi;
    private final String apn;
    private final Kind kind;

    public SgwSessionEvent(String imsi, String apn, Kind kind) {
        this.imsi = Objects.requireNonNull(imsi, "imsi");
        this.apn = Objects.requireNonNull(apn, "apn");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getImsi() {
        return imsi;
    }

    public String getApn() {
        return apn;
    }

    public Kind getKind() {
        return kind;
    }
}