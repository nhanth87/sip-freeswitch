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
        return m;
    }

    public static Map<Integer, String> saaOk() {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(RESULT_CODE, Long.toString(SUCCESS));
        return m;
    }
}
