package et.epc.api;

import java.util.Objects;

public record ApnConfig(String apn, PdnType pdnType, QosProfile qos, boolean ims, String pcscfIpv4) implements java.io.Serializable {
    public ApnConfig {
        Objects.requireNonNull(apn);
        Objects.requireNonNull(pdnType);
        Objects.requireNonNull(qos);
    }

    public static ApnConfig internet() {
        return new ApnConfig("internet", PdnType.IPV4V6, QosProfile.pilotDefault(), false, null);
    }

    public static ApnConfig ims(String pcscfIpv4) {
        return new ApnConfig("ims", PdnType.IPV4V6, new QosProfile(5, 1, true, false,
                1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L), true, pcscfIpv4);
    }
}
