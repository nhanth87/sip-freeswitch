package et.elisa.ims.cx;

import java.util.LinkedHashMap;
import java.util.Map;

/** Cx command codes / AVP codes (TS 29.229) — string AVP map for ra-diameter. */
public final class CxCodec {

    public static final long CX_APP_ID = 16777216L; // 3GPP Cx/Dx
    public static final int UAR = 300;
    public static final int UAA = 300;
    public static final int SAR = 301;
    public static final int SAA = 301;
    public static final int LIR = 302;
    public static final int LIA = 302;
    public static final int MAR = 303;
    public static final int MAA = 303;

    public static final int USER_NAME = 1; // IMPI
    public static final int PUBLIC_IDENTITY = 601;
    public static final int SERVER_NAME = 602;
    public static final int SIP_AUTH_DATA_ITEM = 612;
    public static final int SIP_NUMBER_AUTH_ITEMS = 607;
    public static final int SIP_AUTHENTICATION_SCHEME = 608;
    public static final int SIP_AUTHENTICATE = 609; // RAND||AUTN hex
    public static final int SIP_AUTHORIZATION = 610; // XRES hex
    public static final int CONFIDENTIALITY_KEY = 625;
    public static final int INTEGRITY_KEY = 626;
    public static final int RESULT_CODE = 268;
    public static final int EXPERIMENTAL_RESULT = 297;
    public static final long SUCCESS = 2001;

    /**
     * UAA success (TS 29.229 §6.1.2): DIAMETER_SUCCESS or the 3GPP Experimental-Result
     * DIAMETER_FIRST_REGISTRATION (2001) / SUBSEQUENT_REGISTRATION (2002) /
     * UNREGISTERED_SERVICE (2003) — an HSS sends the latter without Result-Code.
     */
    public static boolean uaaSuccess(Map<Integer, String> uaa) {
        String rc = uaa.get(RESULT_CODE);
        return "2001".equals(rc) || "2002".equals(rc) || "2003".equals(rc);
    }

    /** LIA success (TS 29.229 §6.1.4): DIAMETER_SUCCESS or DIAMETER_UNREGISTERED_SERVICE (2003). */
    public static boolean liaSuccess(Map<Integer, String> lia) {
        String rc = lia.get(RESULT_CODE);
        return "2001".equals(rc) || "2003".equals(rc);
    }

    private CxCodec() {}

    /**
     * MAR for re-synchronisation (TS 29.229 §6.3.13 / TS 33.203): the SIP-Auth-Data-Item
     * carries SIP-Authorization = RAND ‖ AUTS (hex here) from the UE's {@code auts}.
     */
    public static Map<Integer, String> marResyncAvps(String impi, String impu, String serverName,
                                                     String randAutsHex) {
        Map<Integer, String> m = new java.util.LinkedHashMap<>(marAvps(impi, impu, serverName));
        m.put(SIP_AUTHORIZATION, randAutsHex);
        return m;
    }

    public static Map<Integer, String> marAvps(String impi, String impu, String serverName) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(USER_NAME, impi);
        m.put(PUBLIC_IDENTITY, impu);
        m.put(SERVER_NAME, serverName);
        m.put(SIP_NUMBER_AUTH_ITEMS, "1");
        m.put(SIP_AUTHENTICATION_SCHEME, "Digest-AKAv1-MD5");
        return m;
    }

    public static Map<Integer, String> maaAvps(String impi, String randAutnHex, String xresHex,
                                               String ckHex, String ikHex) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(RESULT_CODE, Long.toString(SUCCESS));
        m.put(USER_NAME, impi);
        m.put(SIP_NUMBER_AUTH_ITEMS, "1");
        m.put(SIP_AUTHENTICATION_SCHEME, "Digest-AKAv1-MD5");
        m.put(SIP_AUTHENTICATE, randAutnHex);
        m.put(SIP_AUTHORIZATION, xresHex);
        m.put(CONFIDENTIALITY_KEY, ckHex);
        m.put(INTEGRITY_KEY, ikHex);
        return m;
    }

    public static Map<Integer, String> uarAvps(String impi, String impu) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(USER_NAME, impi);
        m.put(PUBLIC_IDENTITY, impu);
        return m;
    }

    public static Map<Integer, String> uaaAvps(String scscfSipUri) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(RESULT_CODE, Long.toString(SUCCESS));
        m.put(SERVER_NAME, scscfSipUri);
        return m;
    }

    public static Map<Integer, String> lirAvps(String impu) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(PUBLIC_IDENTITY, impu);
        return m;
    }

    public static Map<Integer, String> liaAvps(String scscfSipUri) {
        return uaaAvps(scscfSipUri);
    }

    public static Map<Integer, String> sarAvps(String impi, String impu, String serverName) {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(USER_NAME, impi);
        m.put(PUBLIC_IDENTITY, impu);
        m.put(SERVER_NAME, serverName);
        m.put(SERVER_ASSIGNMENT_TYPE, "1");         // REGISTRATION
        m.put(USER_DATA_ALREADY_AVAILABLE, "0");    // USER_DATA_NOT_AVAILABLE
        return m;
    }

    public static final int SERVER_ASSIGNMENT_TYPE = 614;
    public static final int USER_DATA_ALREADY_AVAILABLE = 624;
    static final long VENDOR_3GPP = 10_415L;

    /**
     * A Cx request as typed AVPs (TS 29.229 §6.1): User-Name is a base AVP, the Cx AVPs are
     * 3GPP-vendor with the M-bit, the authentication scheme / SIP-Authorization sit inside
     * SIP-Auth-Data-Item, plus Vendor-Specific-Application-Id and Auth-Session-State. Sent
     * as the flat map, every value went out as an untyped vendor-less UTF-8 AVP that a
     * conforming HSS drops — a LIR then carried no Public-Identity at all.
     */
    public static java.util.List<com.microjainslee.ra.diameter.avp.DiameterAvp> toWire(Map<Integer, String> m) {
        java.util.List<com.microjainslee.ra.diameter.avp.DiameterAvp> out = new java.util.ArrayList<>();
        out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.grouped(260, 0L, true, java.util.List.of(
                com.microjainslee.ra.diameter.avp.DiameterAvp.u32(266, 0L, VENDOR_3GPP, true),
                com.microjainslee.ra.diameter.avp.DiameterAvp.u32(258, 0L, CX_APP_ID, true))));
        out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.enum32(277, 0L, 1L, true)); // NO_STATE_MAINTAINED
        java.util.List<com.microjainslee.ra.diameter.avp.DiameterAvp> authItem = new java.util.ArrayList<>();
        for (Map.Entry<Integer, String> e : m.entrySet()) {
            String v = e.getValue();
            if (v == null) {
                continue;
            }
            int code = e.getKey();
            switch (code) {
                case USER_NAME -> out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.utf8(USER_NAME, 0L, v, true));
                case PUBLIC_IDENTITY, SERVER_NAME ->
                        out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.utf8(code, VENDOR_3GPP, v, true));
                case SIP_NUMBER_AUTH_ITEMS, SERVER_ASSIGNMENT_TYPE, USER_DATA_ALREADY_AVAILABLE ->
                        out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.u32(code, VENDOR_3GPP, Long.parseLong(v), true));
                case SIP_AUTHENTICATION_SCHEME, SIP_AUTHORIZATION ->
                        authItem.add(com.microjainslee.ra.diameter.avp.DiameterAvp.utf8(code, VENDOR_3GPP, v, true));
                default -> out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.utf8(code, VENDOR_3GPP, v, false));
            }
        }
        if (!authItem.isEmpty()) {
            out.add(com.microjainslee.ra.diameter.avp.DiameterAvp.grouped(SIP_AUTH_DATA_ITEM, VENDOR_3GPP, true, authItem));
        }
        return out;
    }

    public static Map<Integer, String> saaOk() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(RESULT_CODE, Long.toString(SUCCESS));
        return m;
    }
}
