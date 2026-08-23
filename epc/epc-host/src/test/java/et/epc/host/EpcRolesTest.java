package et.epc.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EpcRolesTest {

    @Test
    void atLeastOneRoleRequired() {
        assertThrows(IllegalArgumentException.class, () -> new EpcRoles(false, false, false, false, false, false));
    }

    @Test
    void hssOnlyWiresS6aOnly() {
        EpcRoles r = new EpcRoles(true, false, false, false, false, false);
        assertTrue(r.wireS6a());
        assertFalse(r.wireGtpS11());
        assertFalse(r.wireGtpS5());
        assertFalse(r.wirePfcpSxa());
        assertFalse(r.wirePfcpSxb());
        assertFalse(r.wirePcef());
        assertFalse(r.wirePcrf());
        assertFalse(r.wirePcefStandalone());
        assertFalse(r.wireUp());
        assertTrue(r.wireDiameter());
    }

    @Test
    void pcrfWiresPcrfAndGxRxOnly() {
        EpcRoles r = new EpcRoles(false, false, false, true, false, false);
        assertFalse(r.wireS6a());
        assertFalse(r.wireGtpS11());
        assertTrue(r.wirePcrf());
        assertFalse(r.wirePcef());
        assertFalse(r.wirePcefStandalone());
        assertFalse(r.wireUp());
        assertTrue(r.wireDiameter());
    }

    @Test
    void pcefStandaloneWiresPcefNotPgwcPgw() {
        EpcRoles r = new EpcRoles(false, false, false, false, true, false);
        assertFalse(r.wirePcef()); // embedded PGW-C PCEF is off
        assertTrue(r.wirePcefStandalone());
        assertFalse(r.wireGtpS5());
        assertFalse(r.wireGtpS11());
        assertTrue(r.wireDiameter());
    }

    @Test
    void upWiresUpOnly() {
        EpcRoles r = new EpcRoles(false, false, false, false, false, true);
        assertFalse(r.wireS6a());
        assertFalse(r.wireGtpS11());
        assertFalse(r.wirePcrf());
        assertFalse(r.wirePcef());
        assertTrue(r.wireUp());
        assertFalse(r.wireDiameter());
    }

    @Test
    void sgwcWiresS11S5Sxa() {
        EpcRoles r = new EpcRoles(false, true, false, false, false, false);
        assertFalse(r.wireS6a());
        assertTrue(r.wireGtpS11());
        assertTrue(r.wireGtpS5());
        assertTrue(r.wirePfcpSxa());
        assertFalse(r.wirePfcpSxb());
        assertFalse(r.wirePcef());
        assertFalse(r.wirePcrf());
        assertFalse(r.wirePcefStandalone());
        assertFalse(r.wireUp());
    }

    @Test
    void pgwcWiresS5SxbAndPcef() {
        EpcRoles r = new EpcRoles(false, false, true, false, false, false);
        assertFalse(r.wireS6a());
        assertFalse(r.wireGtpS11());
        assertTrue(r.wireGtpS5());
        assertFalse(r.wirePfcpSxa());
        assertTrue(r.wirePfcpSxb());
        assertTrue(r.wirePcef());
        assertFalse(r.wirePcrf());
        assertFalse(r.wirePcefStandalone());
        assertFalse(r.wireUp());
        assertTrue(r.wireDiameter());
    }

    @Test
    void combinedWiresAll() {
        EpcRoles r = new EpcRoles(true, true, true, true, true, true);
        assertTrue(r.wireS6a());
        assertTrue(r.wireGtpS11());
        assertTrue(r.wireGtpS5());
        assertTrue(r.wirePfcpSxa());
        assertTrue(r.wirePfcpSxb());
        assertTrue(r.wirePcef());
        assertTrue(r.wirePcrf());
        assertTrue(r.wirePcefStandalone());
        assertTrue(r.wireUp());
    }

    @Test
    void fromEnvMapsAllRolesAndKeepsAtLeastOne() {
        EpcRoles r = EpcRoles.fromEnv(false, false, false, true, true, true);
        assertTrue(r.wirePcrf());
        assertTrue(r.wirePcefStandalone());
        assertTrue(r.wireUp());
        assertThrows(IllegalArgumentException.class,
                () -> EpcRoles.fromEnv(false, false, false, false, false, false));
    }

    @Test
    void classicKeepsPcrfPcefUpOff() {
        EpcRoles r = EpcRoles.classic(true, false, true);
        assertTrue(r.wireS6a());
        assertTrue(r.wirePcef()); // PGW-C embeds its PCEF
        assertFalse(r.wirePcefStandalone());
        assertFalse(r.wireUp());   // UP is never in a C-plane JVM
        assertFalse(r.wirePcrf());
    }
}