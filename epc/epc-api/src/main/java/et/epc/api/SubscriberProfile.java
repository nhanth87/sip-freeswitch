package et.epc.api;

import java.util.List;
import java.util.Objects;

public record SubscriberProfile(
        Imsi imsi,
        String msisdn,
        boolean enabled,
        PlmnId homePlmn,
        List<ApnConfig> apns,
        String defaultApn,
        QosProfile subscribedQos,
        int chargingCharacteristics
) implements java.io.Serializable {
    public SubscriberProfile {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(homePlmn);
        apns = List.copyOf(apns == null ? List.of() : apns);
        Objects.requireNonNull(defaultApn);
        Objects.requireNonNull(subscribedQos);
    }

    public ApnConfig apn(String name) {
        return apns.stream().filter(a -> a.apn().equalsIgnoreCase(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown APN " + name));
    }
}
