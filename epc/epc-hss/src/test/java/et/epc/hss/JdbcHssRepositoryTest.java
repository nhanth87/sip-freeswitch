package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JdbcHssRepositoryTest {

    @Test
    void reserveSqnSqlIsAtomicUpdateReturning() {
        assertTrue(JdbcHssRepository.RESERVE_SQN.contains("sqn = sqn + 1"));
        assertTrue(JdbcHssRepository.RESERVE_SQN.contains("RETURNING sqn"));
    }
}
