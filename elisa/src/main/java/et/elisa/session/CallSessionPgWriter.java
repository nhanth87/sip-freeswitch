package et.elisa.session;

import et.elisa.cluster.CallSession;
import et.elisa.persist.SfCallSession;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres copy of {@link CallSession} ({@code sf_call_session}). Fed in batches by the
 * {@link CallSessionService} write-behind so ESL {@code CHANNEL_*} events never wait on
 * PG; own bean so {@code @Transactional} is not a no-op self-invoke.
 */
@ApplicationScoped
public class CallSessionPgWriter {

    /** Latest PG op for one FS channel; {@code ended} stamps {@code ended_at}. */
    public record Op(CallSession session, boolean ended) {}

    @Transactional
    public void writeBatch(List<Op> ops) {
        if (ops.isEmpty()) {
            return;
        }
        List<String> uuids = new ArrayList<>(ops.size());
        ops.forEach(op -> uuids.add(op.session().fsUuid()));
        Map<String, SfCallSession> rows = new HashMap<>();
        for (SfCallSession row : SfCallSession.<SfCallSession>list("fsUuid in ?1", uuids)) {
            rows.put(row.fsUuid, row);
        }
        for (Op op : ops) {
            write(rows.get(op.session().fsUuid()), op);
        }
    }

    private static void write(SfCallSession row, Op op) {
        CallSession s = op.session();
        if (row == null) {
            row = new SfCallSession();
            row.fsUuid = s.fsUuid();
            row.startedAt = s.startedAt();
        }
        row.sipCallId = s.sipCallId();
        row.profileId = s.profileId();
        row.msisdn = s.msisdn();
        row.tenantId = s.tenantId();
        row.elisaNodeId = s.elisaNodeId();
        row.fsNodeId = s.fsNodeId();
        row.direction = s.direction();
        row.state = s.state();
        row.updatedAt = op.ended() ? Instant.now() : s.updatedAt();
        if (op.ended()) {
            row.endedAt = Instant.now();
        }
        row.persist();
    }
}
