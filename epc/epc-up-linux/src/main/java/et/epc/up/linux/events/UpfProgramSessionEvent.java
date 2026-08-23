package et.epc.up.linux.events;

import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.annotations.EventType;
import com.microjainslee.ra.pfcp.PfcpRule;
import et.epc.api.UeAddresses;
import java.util.Objects;

/**
 * N4 user-plane programming event. A non-null {@link #getUe()} requests an
 * install (program) of the bearer rule on the Linux GTP user plane; a null UE
 * requests a delete (teardown). Immutable so it can cross the SLEE event bus.
 */
@EventType(name = "epc.upf.program", vendor = "et.epc", version = "1.0")
public final class UpfProgramSessionEvent implements SleeEvent {

    private final long seid;
    private final PfcpRule rule;
    private final UeAddresses ue;

    public UpfProgramSessionEvent(long seid, PfcpRule rule, UeAddresses ue) {
        this.seid = seid;
        this.rule = Objects.requireNonNull(rule, "rule");
        this.ue = ue;
    }

    public long getSeid() {
        return seid;
    }

    public PfcpRule getRule() {
        return rule;
    }

    /** Null signals a tear-down rather than a program. */
    public UeAddresses getUe() {
        return ue;
    }
}