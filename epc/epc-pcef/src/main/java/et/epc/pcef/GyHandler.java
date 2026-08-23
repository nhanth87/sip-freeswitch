package et.epc.pcef;

import com.microjainslee.ra.diameter.avp.DiameterAvp;
import com.microjainslee.ra.diameter.events.DiameterAnswerEvent;
import et.epc.api.Imsi;
import java.util.List;
import java.util.Objects;

/**
 * Gy (TS 32.299 / RFC 4006) Credit-Control codec for the PCEF→OCS client rail.
 * The PCEF is the Diameter client: it sends CCR-InitiAL (grouped
 * Multiple-Services-Credit-Control) and consumes the OCS CCA
 * (Granted-Service-Unit / Result-Code). Transport stays on {@code diameter-ra}.
 */
public final class GyHandler {

    public static final long APP_ID = 4L;
    public static final int CCR_CMD = 272;

    public static final int RESULT_CODE = 268;
    public static final int SUBSCRIPTION_ID = 443;
    public static final int SUBSCRIPTION_ID_DATA = 444;
    public static final int SUBSCRIPTION_ID_TYPE = 450;
    public static final int CC_REQUEST_TYPE = 416;
    public static final int CC_REQUEST_NUMBER = 415;
    public static final int SERVICE_IDENTIFIER = 439;
    public static final int RATING_GROUP = 432;
    public static final int REQUESTED_SERVICE_UNIT = 437;
    public static final int USED_SERVICE_UNIT = 446;
    public static final int GRANTED_SERVICE_UNIT = 431;
    public static final int MULTIPLE_SERVICES_CREDIT_CONTROL = 456;
    public static final int CC_TOTAL_OCTETS = 421;
    public static final int CC_INPUT_OCTETS = 412;
    public static final int CC_OUTPUT_OCTETS = 414;
    public static final int CC_TIME = 420;

    public static final String REQ_TYPE_INITIAL = "1";
    public static final String REQ_TYPE_UPDATE = "2";
    public static final String REQ_TYPE_TERMINATION = "3";

    private static final long END_USER_IMSI = 1L;
    /** Pilot volume service; rating groups stay RFC4006 base (non-vendor). */
    private static final long DEFAULT_SERVICE_IDENTIFIER = 1000L;

    private GyHandler() {
    }

    /**
     * Grouped CCR-i body: Subscription-Id(443){Type=END_USER_IMSI, Data=IMSI} +
     * Multiple-Services-Credit-Control(456){Service-Identifier, Requested-Service-Unit
     * {CC-Total-Octets}}. Returned as the structured AVP layer of a
     * {@code SendDiameterRequest}.
     */
    public static List<DiameterAvp> ccrInitialStructured(Imsi imsi, long requestedOctets) {
        Objects.requireNonNull(imsi);
        return List.of(
                DiameterAvp.grouped(SUBSCRIPTION_ID, List.of(
                        DiameterAvp.enum32(SUBSCRIPTION_ID_TYPE, 0L, END_USER_IMSI, true),
                        DiameterAvp.utf8(SUBSCRIPTION_ID_DATA, imsi.digits()))),
                DiameterAvp.grouped(MULTIPLE_SERVICES_CREDIT_CONTROL, List.of(
                        DiameterAvp.u32(SERVICE_IDENTIFIER, DEFAULT_SERVICE_IDENTIFIER),
                        DiameterAvp.grouped(REQUESTED_SERVICE_UNIT, List.of(
                                DiameterAvp.u32(CC_TOTAL_OCTETS, requestedOctets))))));
    }

    /** Parsed Gy CCA: Result-Code plus the first MSCC Granted-Service-Unit quota. */
    public record Gcca(long resultCode, Long serviceIdentifier, Long grantedOctets, Long grantedTime) {
        public static Gcca from(DiameterAnswerEvent answer) {
            Objects.requireNonNull(answer);
            Long serviceId = null;
            Long octets = null;
            Long time = null;
            for (DiameterAvp avp : asList(answer.avpsStructured())) {
                if (avp.code() == MULTIPLE_SERVICES_CREDIT_CONTROL) {
                    for (DiameterAvp item : children(avp)) {
                        if (item.code() == SERVICE_IDENTIFIER) {
                            serviceId = item.asUnsigned32();
                        } else if (item.code() == GRANTED_SERVICE_UNIT) {
                            for (DiameterAvp granted : children(item)) {
                                if (granted.code() == CC_TOTAL_OCTETS) {
                                    octets = granted.asUnsigned32();
                                } else if (granted.code() == CC_TIME) {
                                    time = granted.asUnsigned32();
                                }
                            }
                        }
                    }
                }
            }
            return new Gcca(answer.resultCode(), serviceId, octets, time);
        }

        private static List<DiameterAvp> asList(List<DiameterAvp> avps) {
            return avps == null ? List.of() : avps;
        }

        private static List<DiameterAvp> children(DiameterAvp avp) {
            return avp.children() == null ? List.of() : avp.children();
        }
    }
}