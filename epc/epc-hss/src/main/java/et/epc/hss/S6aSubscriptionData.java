package et.epc.hss;

import et.epc.api.ApnConfig;
import et.epc.api.PdnType;
import et.epc.api.QosProfile;
import et.epc.api.SubscriberProfile;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the 3GPP TS 29.272 S6a ULA Subscription-Data for {@link HssService}.
 *
 * <p>{@code ra-diameter} carries a {@code Map<Integer,String>} (no nested groups),
 * so grouped AVPs are flattened to strings. The flat map produced by
 * {@link #ulaAvps(SubscriberProfile)} is the same shape the Diameter seam uses.
 * The grouped APN-Configuration-Profile is JSON (not binary TLVs) so callers and
 * tests can parse it without a second Diameter codec.</p>
 *
 * <p>Flat keys and their wire (grouped/binary) equivalents:</p>
 * <ul>
 *   <li>1400 Subscription-Data &#8594; JSON (grouped 1400 on wire)</li>
 *   <li>1429 APN-Configuration-Profile &#8594; JSON array inside it (grouped 1429
 *       &#8594; repeated grouped 1430 APN-Configuration)</li>
 *   <li>1435 AMBR (UE-AMBR) &#8594; {@code ul=...;dl=...} (grouped 1435 &#8594;
 *       516 Max-Requested-Bandwidth-UL / 517 ...-DL)</li>
 *   <li>1424 Subscriber-Status &#8594; 0=SERVICE_GRANTED, 1=BARRING (Enumerated)</li>
 *   <li>1417 Network-Access-Mode &#8594; 2=ONLY_PACKET (Enumerated)</li>
 * </ul>
 *
 * <p>PDN-Type uses 0=IPv4, 1=IPv6, 2=IPv4v6. Default-bearer QoS is non-GBR:
 * MBR/GBR are intentionally absent and the APN-AMBR governs.</p>
 */
public final class S6aSubscriptionData {

    /** TS 29.272 APN-Configuration-Profile (grouped on wire, JSON in flat map). */
    public static final int APN_CONFIGURATION_PROFILE = 1429;

    /** TS 29.272 AMBR (grouped on wire); used for the UE-AMBR flat entry. */
    public static final int AMBR = 1435;

    /** TS 29.272 Subscriber-Status (Enumerated). */
    public static final int SUBSCRIBER_STATUS = 1424;

    /** TS 29.272 Network-Access-Mode (Enumerated). */
    public static final int NETWORK_ACCESS_MODE = 1417;

    public static final String SUBSCRIBER_STATUS_SERVICE_GRANTED = "0";
    public static final String SUBSCRIBER_STATUS_OPERATOR_DETERMINED_BARRING = "1";
    public static final String NETWORK_ACCESS_MODE_ONLY_PACKET = "2";

    private S6aSubscriptionData() {}

    /** Flat AVPs a ULA should carry: Subscription-Data, AMBR, Subscriber-Status, Network-Access-Mode. */
    public static Map<Integer, String> ulaAvps(SubscriberProfile profile) {
        LinkedHashMap<Integer, String> avps = new LinkedHashMap<>();
        avps.put(S6aAvp.SUBSCRIPTION_DATA, subscriptionData(profile));
        avps.put(AMBR, ueAmbr(profile));
        avps.put(SUBSCRIBER_STATUS, SUBSCRIBER_STATUS_SERVICE_GRANTED);
        avps.put(NETWORK_ACCESS_MODE, NETWORK_ACCESS_MODE_ONLY_PACKET);
        return avps;
    }

    /** Compact JSON encoding of the grouped 1400 Subscription-Data. Deterministic order. */
    public static String subscriptionData(SubscriberProfile profile) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        json.append("\"imsi\":\"").append(escape(profile.imsi().digits())).append('"');
        json.append(",\"subscriberStatus\":").append(SUBSCRIBER_STATUS_SERVICE_GRANTED);
        json.append(",\"networkAccessMode\":").append(NETWORK_ACCESS_MODE_ONLY_PACKET);
        json.append(",\"defaultApn\":\"").append(escape(profile.defaultApn())).append('"');
        QosProfile sub = profile.subscribedQos();
        json.append(",\"ueAmbrUplinkBps\":").append(sub.ueAmbrUlBps());
        json.append(",\"ueAmbrDownlinkBps\":").append(sub.ueAmbrDlBps());
        json.append(",\"apnConfigurationProfile\":[");
        for (int i = 0; i < profile.apns().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            appendApnConfiguration(profile.apns().get(i), i + 1, json);
        }
        json.append("]}");
        return json.toString();
    }

    /** {@code ul=<bps>;dl=<bps>} — the compact AMBR leaf encoding in the flat map. */
    public static String ueAmbr(SubscriberProfile profile) {
        QosProfile q = profile.subscribedQos();
        return "ul=" + q.ueAmbrUlBps() + ";dl=" + q.ueAmbrDlBps();
    }

    private static void appendApnConfiguration(ApnConfig apn, int contextId, StringBuilder json) {
        json.append('{');
        json.append("\"contextId\":").append(contextId);
        json.append(",\"apn\":\"").append(escape(apn.apn())).append('"');
        json.append(",\"pdnType\":\"").append(pdnType(apn.pdnType())).append('"');
        json.append(",\"qos\":{");
        QosProfile q = apn.qos();
        json.append("\"qci\":").append(q.qci());
        json.append(",\"arpPriorityLevel\":").append(q.arpPriority());
        json.append(",\"preemptionCapability\":").append(q.preemptionCapability());
        json.append(",\"preemptionVulnerability\":").append(q.preemptionVulnerability());
        json.append(",\"apnAmbrUplinkBps\":").append(q.apnAmbrUlBps());
        json.append(",\"apnAmbrDownlinkBps\":").append(q.apnAmbrDlBps());
        json.append("}}");
    }

    private static String pdnType(PdnType type) {
        return switch (type) {
            case IPV4 -> "IPV4";
            case IPV6 -> "IPV6";
            case IPV4V6 -> "IPV4V6";
        };
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}