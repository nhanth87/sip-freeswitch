package et.elisa.ims.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ImsIdentityKeysTest {

    @Test
    void userPartFromSipUriAndBare() {
        assertEquals("alice", ImsIdentityKeys.userPart("sip:alice@ims.lab"));
        assertEquals("alice", ImsIdentityKeys.userPart("alice"));
        assertEquals("alice", ImsIdentityKeys.userPart("alice@ims.lab"));
    }

    @Test
    void realmQualifiedKeysAreExact() {
        assertEquals("sip:alice@ims.lab", ImsIdentityKeys.impu("alice", "ims.lab"));
        assertEquals("alice@ims.lab", ImsIdentityKeys.impi("alice", "ims.lab"));
        assertEquals("ims.lab", ImsIdentityKeys.realmOrDefault(null));
    }

    @Test
    void bindingKeysMapHostAndBareUserToImpu() {
        assertTrue(ImsIdentityKeys.bindingKeys("bob").contains("sip:bob@ims.lab"));
        assertTrue(ImsIdentityKeys.bindingKeys("sip:bob@10.0.0.1:5060").contains("sip:bob@ims.lab"));
        assertTrue(ImsIdentityKeys.bindingKeys("sip:bob@ims.lab").contains("sip:bob@ims.lab"));
    }

    @Test
    @Timeout(10)
    void hundredThousandBindingKeysStayExact() {
        for (int i = 0; i < 100_000; i++) {
            String user = "u" + i;
            assertTrue(ImsIdentityKeys.bindingKeys(user).contains("sip:" + user + "@ims.lab"));
        }
    }
}
