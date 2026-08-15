package et.elisa.cluster;

import java.io.Serializable;
import java.time.Instant;

/** Hot-path call session (ISPN) — SIP Call-ID ↔ FS Unique-ID ↔ profile. */
public record CallSession(
        String fsUuid,
        String sipCallId,
        Long profileId,
        String msisdn,
        String tenantId,
        String elisaNodeId,
        String fsNodeId,
        String direction,
        String state,
        Instant startedAt,
        Instant updatedAt
) implements Serializable {
    private static final long serialVersionUID = 1L;

    public CallSession {
        sipCallId = et.elisa.cmp.SipCallIds.canonical(sipCallId);
    }

    public CallSession withState(String newState) {
        return new CallSession(fsUuid, sipCallId, profileId, msisdn, tenantId,
                elisaNodeId, fsNodeId, direction, newState, startedAt, Instant.now());
    }
}
