package et.epc.api;

import java.util.List;
import java.util.Objects;

import com.microjainslee.ra.gtpv2c.Gtpv2Ies;

public record ApnConfig(String apn, PdnType pdnType, QosProfile qos, boolean ims,
                        String pcscfIpv4, String dnsIpv4) implements java.io.Serializable {
    public ApnConfig {
        Objects.requireNonNull(apn);
        Objects.requireNonNull(pdnType);
        Objects.requireNonNull(qos);
    }

    /** Compat ctor — IMS APN without DNS override. */
    public ApnConfig(String apn, PdnType pdnType, QosProfile qos, boolean ims, String pcscfIpv4) {
        this(apn, pdnType, qos, ims, pcscfIpv4, null);
    }

    public static ApnConfig internet() {
        return new ApnConfig("internet", PdnType.IPV4V6, QosProfile.pilotDefault(), false, null, null);
    }

    public static ApnConfig ims(String pcscfIpv4) {
        return ims(pcscfIpv4, null);
    }

    public static ApnConfig ims(String pcscfIpv4, String dnsIpv4) {
        return new ApnConfig("ims", PdnType.IPV4V6, new QosProfile(5, 1, true, false,
                1_000_000_000L, 1_000_000_000L, 1_000_000_000L, 1_000_000_000L),
                true, pcscfIpv4, dnsIpv4);
    }

    /**
     * EPS PCO containers this APN hands to the UE (TS 24.008 §10.5.6.11):
     * P-CSCF IPv4 (+ optional DNS IPv4) on the IMS APN only.
     */
    public List<Gtpv2Ies.PcoContainer> pcoContainers() {
        if (!ims || (pcscfIpv4 == null && dnsIpv4 == null)) {
            return List.of();
        }
        try {
            var out = new java.util.ArrayList<Gtpv2Ies.PcoContainer>();
            if (pcscfIpv4 != null) {
                out.add(Gtpv2Ies.ipv4Container(Gtpv2Ies.PCO_P_CSCF_IPV4,
                        java.net.InetAddress.getByName(pcscfIpv4.trim())));
            }
            if (dnsIpv4 != null) {
                out.add(Gtpv2Ies.ipv4Container(Gtpv2Ies.PCO_DNS_SERVER_IPV4,
                        java.net.InetAddress.getByName(dnsIpv4.trim())));
            }
            return out;
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("APN PCO address not an IPv4 literal", e);
        }
    }
}
