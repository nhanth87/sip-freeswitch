package et.sipfreeswitch.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SipCpsMetricsTest {

    @Test
    void inviteAndDialogLifecycle() {
        SipCpsMetrics m = new SipCpsMetrics();
        m.onInvite();
        m.onInvite();
        assertEquals(2, m.inviteCount());
        assertEquals(2, m.activeDialogs());
        m.onDialogEnd();
        assertEquals(1, m.activeDialogs());
        m.onReject();
        assertEquals(1, m.rejectCount());
    }
}
