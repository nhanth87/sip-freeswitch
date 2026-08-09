package et.elisa.persist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ImsSubscriberDirectoryUserIdTest {

    @Test
    void prefersMsisdn() {
        ImsSubscriber s = new ImsSubscriber();
        s.msisdn = "251911000111";
        s.impu = "sip:alice@ims.lab";
        assertEquals("251911000111", s.directoryUserId());
    }

    @Test
    void fallsBackToImpuUserPart() {
        ImsSubscriber s = new ImsSubscriber();
        s.impu = "sip:alice@ims.lab";
        s.impi = "alice@ims.lab";
        assertEquals("alice", s.directoryUserId());
    }
}
