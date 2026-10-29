package et.elisa.ims.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.elisa.ims.aka.AkaMd5;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pending-auth map behavior without DB (no @Transactional subscriber). */
class HssLitePendingAuthTest {

    @Test
    void takePending_removesBothCallIdAndImpiKeys() throws Exception {
        HssLiteService hss = new HssLiteService();
        byte[] xres = AkaMd5.parseHex("aabbccddeeff0011");
        var pending = new HssLiteService.PendingAuth("u@ims.lab", "sip:u@ims.lab", xres, "bm9uY2U=");
        // simulate handleMar dual-key put via reflection on private map
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<String, HssLiteService.PendingAuth>)
                ImsReflect.get(hss, "pending");
        map.put("call-1", pending);
        map.put("u@ims.lab", pending);

        Optional<HssLiteService.PendingAuth> got = hss.takePending("call-1");
        assertTrue(got.isPresent());
        assertEquals("bm9uY2U=", got.get().nonce());
        assertTrue(map.isEmpty(), "BUG: impi key left behind after takePending(callId)");
    }

    @Test
    void peekDoesNotRemove() throws Exception {
        HssLiteService hss = new HssLiteService();
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<String, HssLiteService.PendingAuth>)
                ImsReflect.get(hss, "pending");
        map.put("c", new HssLiteService.PendingAuth("i", "p", new byte[] {1}, "n"));
        assertTrue(hss.peekPending("c").isPresent());
        assertTrue(map.containsKey("c"));
    }

    static final class ImsReflect {
        static Object get(Object o, String name) throws Exception {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        }
    }
}
