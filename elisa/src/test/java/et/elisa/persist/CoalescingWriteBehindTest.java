package et.elisa.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoalescingWriteBehindTest {

    @Test
    void latestOpPerKeyWinsAndFlushWritesOneBatch() {
        List<List<String>> batches = new ArrayList<>();
        CoalescingWriteBehind<String> wb = new CoalescingWriteBehind<>("t", batches::add, 60_000);
        wb.put("call-1", "INVITE");
        wb.put("call-1", "EARLY");
        wb.put("call-2", "INVITE");
        wb.put("call-1", "CONFIRMED");

        assertEquals("CONFIRMED", wb.pendingFor("call-1"));
        wb.flush();

        assertEquals(1, batches.size());
        assertEquals(2, batches.getFirst().size());
        assertEquals(List.of("CONFIRMED", "INVITE"), batches.getFirst().stream().sorted().toList());
        assertEquals(0, wb.pendingCount());
        assertEquals(2, wb.writtenCount());
        wb.stop();
    }

    @Test
    void failedBatchIsCountedNotRequeued() {
        CoalescingWriteBehind<String> wb = new CoalescingWriteBehind<>("t", batch -> {
            throw new IllegalStateException("pg down");
        }, 60_000);
        wb.put("call-1", "INVITE");
        wb.flush();

        assertEquals(1, wb.failedCount());
        assertEquals(0, wb.pendingCount());
        wb.stop();
    }
}
