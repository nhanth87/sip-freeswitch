package et.elisa.recovery;

import java.io.Serializable;
import java.util.Objects;

/**
 * Dual-write fencing token for dialog ownership (R3).
 * CAS claim bumps {@link #generation()} so a stale node cannot dual-spawn.
 */
public final class DialogOwner implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String callId;
    private final String ownerNodeId;
    private final long generation;
    private final long updatedAtEpochMs;

    public DialogOwner(String callId, String ownerNodeId, long generation, long updatedAtEpochMs) {
        this.callId = Objects.requireNonNull(callId, "callId");
        this.ownerNodeId = Objects.requireNonNull(ownerNodeId, "ownerNodeId");
        this.generation = generation;
        this.updatedAtEpochMs = updatedAtEpochMs;
    }

    public String callId() {
        return callId;
    }

    public String ownerNodeId() {
        return ownerNodeId;
    }

    public long generation() {
        return generation;
    }

    public long updatedAtEpochMs() {
        return updatedAtEpochMs;
    }

    public DialogOwner withOwner(String newOwnerNodeId, long newGeneration, long nowMs) {
        return new DialogOwner(callId, newOwnerNodeId, newGeneration, nowMs);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DialogOwner that)) {
            return false;
        }
        return generation == that.generation
                && updatedAtEpochMs == that.updatedAtEpochMs
                && callId.equals(that.callId)
                && ownerNodeId.equals(that.ownerNodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(callId, ownerNodeId, generation, updatedAtEpochMs);
    }

    @Override
    public String toString() {
        return "DialogOwner[callId=" + callId
                + ", ownerNodeId=" + ownerNodeId
                + ", generation=" + generation
                + ']';
    }
}
