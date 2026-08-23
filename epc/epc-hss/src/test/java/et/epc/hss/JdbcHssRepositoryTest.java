package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JdbcHssRepositoryTest {

    @Test
    void reserveSqnSqlIsAtomicUpdateReturning() {
        assertTrue(JdbcHssRepository.RESERVE_SQN.contains("sqn = sqn + 1"));
        assertTrue(JdbcHssRepository.RESERVE_SQN.contains("RETURNING sqn"));
        // A single UPDATE…RETURNING statement — never a read (SELECT sqn) then a separate write.
        assertFalse(JdbcHssRepository.RESERVE_SQN.contains("SELECT"));
    }

    /**
     * Round-trip contract without an embedded Postgres: every field {@code find()}
     * reads back must be written by the {@code save()} upsert. If the INSERT column
     * list ever drifts from SELECT_FIELDS, a persisted subscriber would silently lose
     * a field on read-back. This pins persist→read coverage in SQL shape.
     */
    @Test
    void saveUpsertWritesEveryFieldFindReadsBack() {
        Set<String> readCols = csvColumns(JdbcHssRepository.SELECT_FIELDS);

        String header = JdbcHssRepository.UPSERT_SUBSCRIBER.substring(
                JdbcHssRepository.UPSERT_SUBSCRIBER.indexOf("epc_subscriber ("));
        String insertList = header.substring(header.indexOf('(') + 1, header.indexOf(')'));
        Set<String> writeCols = csvColumns(insertList);

        for (String col : readCols) {
            assertTrue(writeCols.contains(col),
                    "SELECT_FIELDS column '" + col + "' must be written by the save() upsert");
        }
    }

    /** Ki/OPc/AMF live encrypted in {@code epc_secret}; the row only references them by {@code key_ref}. */
    @Test
    void subscriberRowNeverCarriesSecretMaterial() {
        String header = JdbcHssRepository.UPSERT_SUBSCRIBER.substring(
                JdbcHssRepository.UPSERT_SUBSCRIBER.indexOf("epc_subscriber ("));
        String insertList = header.substring(header.indexOf('(') + 1, header.indexOf(')'));
        Set<String> writeCols = csvColumns(insertList);

        assertTrue(writeCols.contains("key_ref"), "subscriber row must reference keys via key_ref");
        assertFalse(writeCols.contains("ki"), "subscriber row must never store Ki");
        assertFalse(writeCols.contains("opc"), "subscriber row must never store OPc");
        assertFalse(writeCols.contains("amf"), "subscriber row must never store AMF");
        assertFalse(writeCols.contains("ki_enc"));
        assertFalse(writeCols.contains("opc_enc"));
        assertFalse(writeCols.contains("amf_enc"));
    }

    private static Set<String> csvColumns(String csv) {
        Set<String> cols = new HashSet<>();
        for (String c : csv.split(",")) {
            if (!c.isBlank()) {
                cols.add(c.trim());
            }
        }
        return cols;
    }
}
