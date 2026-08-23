package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.PlmnId;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class S6aSubscriptionDataTest {

    private static final Imsi IMSI = new Imsi("001010000000001");
    private static final PlmnId HOME = new PlmnId("001", "01");

    private static SubscriberProfile profile() {
        QosProfile sub = new QosProfile(9, 8, false, true,
                1_000_000_000L, 1_000_000_000L, 512_000L, 1_024_000L);
        return new SubscriberProfile(IMSI, "1234567890", true, HOME,
                List.of(ApnConfig.internet(), ApnConfig.ims("192.0.2.10")),
                "internet", sub, 0);
    }

    @Test
    void ulaAvpsCarryBothApnsAmbrAndSubscriberStatus() {
        Map<Integer, String> avps = S6aSubscriptionData.ulaAvps(profile());
        String sd = avps.get(S6aAvp.SUBSCRIPTION_DATA);
        assertTrue(sd.contains("\"internet\""));
        assertTrue(sd.contains("\"ims\""));
        assertTrue(sd.contains("\"imsi\":\"001010000000001\""));
        assertTrue(sd.contains("\"qci\":5"));
        assertEquals("ul=512000;dl=1024000", avps.get(S6aSubscriptionData.AMBR));
        assertEquals("0", avps.get(S6aSubscriptionData.SUBSCRIBER_STATUS));
        assertEquals("2", avps.get(S6aSubscriptionData.NETWORK_ACCESS_MODE));
    }

    @Test
    void groupedAvpCodesMatchTs29_272() {
        assertEquals(1429, S6aSubscriptionData.APN_CONFIGURATION_PROFILE);
        assertEquals(1424, S6aSubscriptionData.SUBSCRIBER_STATUS);
        assertEquals(1417, S6aSubscriptionData.NETWORK_ACCESS_MODE);
    }
}