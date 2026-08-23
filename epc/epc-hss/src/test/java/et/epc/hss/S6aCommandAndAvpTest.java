package et.epc.hss;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class S6aCommandAndAvpTest {

    @Test
    void s6aCommandCodesMatchTs29_272() {
        assertEquals(318, S6aCommand.AIR.code);
        assertEquals(318, S6aCommand.AIA.code);
        assertEquals(316, S6aCommand.ULR.code);
        assertEquals(317, S6aCommand.CLR.code);
        assertEquals(321, S6aCommand.PUR.code);
        assertEquals(319, S6aCommand.IDR.code);
        assertEquals(320, S6aCommand.DSR.code);
        assertEquals(322, S6aCommand.RSR.code);
        assertEquals(323, S6aCommand.NOR.code);
        assertEquals(16_777_251L, S6aCommand.APP_ID);
    }

    @Test
    void cxCommandCodesMatchTs29_229() {
        assertEquals(300, CxCommand.UAR.code);
        assertEquals(300, CxCommand.UAA.code);
        assertEquals(301, CxCommand.SAR.code);
        assertEquals(302, CxCommand.LIR.code);
        assertEquals(303, CxCommand.MAR.code);
        assertEquals(304, CxCommand.RTR.code);
        assertEquals(305, CxCommand.PPR.code);
        assertEquals(16_777_216L, CxCommand.APP_ID);
    }

    @Test
    void s6aAvpCodesMatchSpec() {
        assertEquals(1, S6aAvp.USER_NAME);
        assertEquals(268, S6aAvp.RESULT_CODE);
        assertEquals(264, S6aAvp.ORIGIN_HOST);
        assertEquals(1407, S6aAvp.VISITED_PLMN_ID);
        assertEquals(1411, S6aAvp.RE_SYNCHRONIZATION_INFO);
        assertEquals(1447, S6aAvp.RAND);
        assertEquals(1448, S6aAvp.XRES);
        assertEquals(1449, S6aAvp.AUTN);
        assertEquals(1450, S6aAvp.KASME);
        assertEquals(1400, S6aAvp.SUBSCRIPTION_DATA);
        assertEquals(1420, S6aAvp.CANCELLATION_TYPE);
        assertEquals(2001L, S6aAvp.SUCCESS);
        assertEquals(4181L, S6aAvp.AUTHENTICATION_DATA_UNAVAILABLE);
        assertEquals(5001L, S6aAvp.ERROR_USER_UNKNOWN);
    }
}