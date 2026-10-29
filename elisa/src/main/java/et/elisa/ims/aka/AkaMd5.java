package et.elisa.ims.aka;

import et.elisa.ims.digest.SipDigestAuth;
import et.epc.crypto.UmtsVector;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * IMS AKA over SIP Digest — {@code algorithm=AKAv1-MD5} (RFC 3310, TS 33.203 §6.1,
 * TS 24.229 §5.4.1.2). The authentication vector itself is the TS 33.102 quintuplet from
 * {@code epc-crypto} (MILENAGE, TS 35.206 — verified against TS 35.208 test sets):
 * AUTN = (SQN ⊕ AK) ‖ AMF ‖ MAC-A.
 *
 * <ul>
 *   <li>nonce = base64(RAND ‖ AUTN) (RFC 3310 §3.2)</li>
 *   <li>the UE answers with an RFC 2617 digest whose password is RES (octets), so the
 *       S-CSCF verifies it with XRES as the password (§3.4) — never by comparing RES
 *       in the clear</li>
 *   <li>synchronisation failure: the UE sends {@code auts=base64(AUTS)} (§3.4)</li>
 * </ul>
 * Ki/OPc are hex strings (32 hex = 16 bytes).
 */
public final class AkaMd5 {

    private static final HexFormat HEX = HexFormat.of();

    private AkaMd5() {}

    /** @param sqn the SQN embedded in AUTN (freshly reserved by the HSS, TS 33.102 Annex C) */
    public record Vector(byte[] rand, byte[] autn, byte[] xres, byte[] ck, byte[] ik, long sqn) {}

    public static Vector generate(byte[] ki, byte[] opc, byte[] amf, long sqn) {
        UmtsVector v = UmtsVector.generate(ki, opc, amf, sqn);
        return new Vector(v.rand(), v.autn(), v.xres(), v.ck(), v.ik(), sqn);
    }

    /** RFC 3310 §3.2: nonce = base64(RAND ‖ AUTN). */
    public static String nonce(byte[] rand, byte[] autn) {
        byte[] both = new byte[rand.length + autn.length];
        System.arraycopy(rand, 0, both, 0, rand.length);
        System.arraycopy(autn, 0, both, rand.length, autn.length);
        return Base64.getEncoder().encodeToString(both);
    }

    /** WWW-Authenticate Digest AKAv1-MD5 challenge value (without header name). */
    public static String wwwAuthenticate(String realm, byte[] rand, byte[] autn) {
        return "Digest realm=\"" + realm + "\", nonce=\"" + nonce(rand, autn)
                + "\", algorithm=AKAv1-MD5, qop=\"auth\"";
    }

    /** RAND from a base64 AKA nonce, or {@code null}. */
    public static byte[] randFromNonce(String nonce) {
        byte[] raw = decodeBase64(nonce);
        return raw == null || raw.length < 32 ? null : java.util.Arrays.copyOf(raw, 16);
    }

    /**
     * RFC 3310 §3.4 / RFC 2617: HA1 = MD5(username ":" realm ":" XRES), XRES taken as
     * octets; the response is then checked like any Digest response.
     */
    public static boolean verifyResponse(String authorization, byte[] xres, String method, String expectedNonce) {
        if (authorization == null || xres == null || xres.length == 0 || expectedNonce == null) {
            return false;
        }
        String username = SipDigestAuth.param(authorization, "username");
        String realm = SipDigestAuth.param(authorization, "realm");
        if (username == null || realm == null) {
            return false;
        }
        return SipDigestAuth.verify(authorization, ha1(username, realm, xres), method, expectedNonce);
    }

    /** Hex HA1 with a binary password (RES/XRES). */
    public static String ha1(String username, String realm, byte[] password) {
        try {
            ByteArrayOutputStream in = new ByteArrayOutputStream();
            in.writeBytes((username + ":" + realm + ":").getBytes(StandardCharsets.UTF_8));
            in.writeBytes(password);
            return HEX.formatHex(MessageDigest.getInstance("MD5").digest(in.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    /** RFC 3310 §3.4: {@code auts} parameter (base64 of the 14-octet AUTS), or {@code null}. */
    public static byte[] auts(String authorization) {
        byte[] raw = decodeBase64(SipDigestAuth.param(authorization, "auts"));
        return raw == null || raw.length != 14 ? null : raw;
    }

    /** True when the Authorization carries a non-empty Digest {@code response}. */
    public static boolean hasResponse(String authorization) {
        String r = SipDigestAuth.param(authorization, "response");
        return r != null && !r.isBlank();
    }

    public static byte[] parseHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return new byte[16];
        }
        String h = hex.trim().replace(" ", "");
        if (h.length() % 2 != 0) {
            h = "0" + h;
        }
        return HEX.parseHex(h);
    }

    public static String toHex(byte[] b) {
        return HEX.formatHex(b == null ? new byte[0] : b);
    }

    private static byte[] decodeBase64(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
