package et.elisa.recovery;

/**
 * Identity of a recoverable dialog / session correlate.
 *
 * @param correlate         sipCallId or fsUuid
 * @param kind              correlate kind
 * @param preferredSbbClass simple or FQN SBB class hint (nullable)
 * @param homeNodeId        I7 sticky home (nullable — derived from checkpoint)
 */
public record RecoveryKey(
        String correlate,
        CorrelateKind kind,
        String preferredSbbClass,
        String homeNodeId
) {
    public static RecoveryKey sipCallId(String callId) {
        return new RecoveryKey(callId, CorrelateKind.SIP_CALL_ID, "ScscfInviteSbb", null);
    }

    public static RecoveryKey sipCallId(String callId, String homeNodeId) {
        return new RecoveryKey(callId, CorrelateKind.SIP_CALL_ID, "ScscfInviteSbb", homeNodeId);
    }

    public static RecoveryKey fsUuid(String fsUuid) {
        return new RecoveryKey(fsUuid, CorrelateKind.FS_UUID, "CallSessionSbb", null);
    }
}
