package et.epc.host;

import com.microjainslee.ra.diameter.command.SendDiameterAnswer;
import com.microjainslee.ra.diameter.events.DiameterRequestEvent;
import et.epc.crypto.Bytes;
import et.epc.hss.S6aAvp;
import et.epc.hss.S6aCommand;
import et.epc.hss.S6aMessage;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps vendor {@code ra-diameter} events onto {@link et.epc.hss.HssService}.
 * Diameter transport is only {@code diameter-ra} — never a second stack.
 */
public final class S6aDiameterMapper {

    public static final long S6A_APP_ID = 16_777_251L;
    public static final long CX_APP_ID = 16_777_216L;
    public static final long GX_APP_ID = 16_777_238L;
    public static final long GY_APP_ID = 4L;

    private S6aDiameterMapper() {}

    public static boolean isS6a(DiameterRequestEvent ev) {
        return ev.applicationId() == S6A_APP_ID;
    }

    public static S6aMessage toS6a(DiameterRequestEvent ev) {
        S6aCommand cmd = command(ev.commandCode(), true);
        S6aMessage.Builder b = S6aMessage.builder(cmd, true);
        Map<Integer, String> avps = ev.avps() == null ? Map.of() : ev.avps();
        putUtf8(b, avps, S6aAvp.USER_NAME);
        putUtf8(b, avps, S6aAvp.ORIGIN_HOST);
        if (avps.containsKey(S6aAvp.VISITED_PLMN_ID)) {
            b.put(S6aAvp.VISITED_PLMN_ID, hexOrRaw(avps.get(S6aAvp.VISITED_PLMN_ID)));
        }
        if (avps.containsKey(S6aAvp.RAND)) {
            b.put(S6aAvp.RAND, hexOrRaw(avps.get(S6aAvp.RAND)));
        }
        if (avps.containsKey(S6aAvp.RE_SYNCHRONIZATION_INFO)) {
            b.put(S6aAvp.RE_SYNCHRONIZATION_INFO, hexOrRaw(avps.get(S6aAvp.RE_SYNCHRONIZATION_INFO)));
        }
        if (ev.originHost() != null && b.build().avpUtf8(S6aAvp.ORIGIN_HOST) == null) {
            b.putUtf8(S6aAvp.ORIGIN_HOST, ev.originHost());
        }
        return b.build();
    }

    public static SendDiameterAnswer toAnswer(DiameterRequestEvent req, S6aMessage rsp) {
        Map<Integer, String> avps = new LinkedHashMap<>();
        rsp.avps().forEach((code, raw) -> {
            if (code == S6aAvp.USER_NAME || code == S6aAvp.SUBSCRIPTION_DATA
                    || code == S6aAvp.CANCELLATION_TYPE) {
                avps.put(code, new String(raw, StandardCharsets.UTF_8));
            } else if (code == S6aAvp.RESULT_CODE) {
                avps.put(code, Integer.toUnsignedString(bytesToInt(raw)));
            } else {
                avps.put(code, HexFormat.of().formatHex(raw));
            }
        });
        long result = rsp.avp(S6aAvp.RESULT_CODE) == null
                ? SendDiameterAnswer.SUCCESS
                : Integer.toUnsignedLong(bytesToInt(rsp.avp(S6aAvp.RESULT_CODE)));
        return new SendDiameterAnswer(
                req.sessionId(), S6A_APP_ID, req.commandCode(), result,
                req.hopByHopId(), req.endToEndId(), avps);
    }

    private static void putUtf8(S6aMessage.Builder b, Map<Integer, String> avps, int code) {
        String v = avps.get(code);
        if (v != null) {
            b.putUtf8(code, v);
        }
    }

    private static byte[] hexOrRaw(String s) {
        String t = s.trim().replace(" ", "");
        if (t.length() % 2 == 0 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return Bytes.parseHex(t);
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static int bytesToInt(byte[] v) {
        int x = 0;
        for (byte b : v) {
            x = (x << 8) | (b & 0xff);
        }
        return x;
    }

    private static S6aCommand command(int code, boolean request) {
        return switch (code) {
            case 318 -> request ? S6aCommand.AIR : S6aCommand.AIA;
            case 316 -> request ? S6aCommand.ULR : S6aCommand.ULA;
            case 317 -> request ? S6aCommand.CLR : S6aCommand.CLA;
            case 321 -> request ? S6aCommand.PUR : S6aCommand.PUA;
            case 319 -> request ? S6aCommand.IDR : S6aCommand.IDA;
            case 320 -> request ? S6aCommand.DSR : S6aCommand.DSA;
            case 322 -> request ? S6aCommand.RSR : S6aCommand.RSA;
            case 323 -> request ? S6aCommand.NOR : S6aCommand.NOA;
            default -> throw new IllegalArgumentException("S6a command " + code);
        };
    }
}
