package et.epc.api;

import java.util.List;

import com.microjainslee.ra.gtpv2c.Gtpv2Ie;
import com.microjainslee.ra.gtpv2c.Gtpv2Ies;

/**
 * Process-wide EPS PCO defaults (TS 23.401 §5.3.1.2 / TS 24.008 §10.5.6.11):
 * the P-CSCF / DNS addresses handed to the UE in Create Session Response.
 * Wired once at boot from {@code epc.pco.*} env props (D2: advertise the Gm
 * VIP, never a bare node IP). Empty = feature off for that container.
 */
public final class PcoDefaults {

    private static volatile String pcscfIpv4;
    private static volatile String dnsIpv4;

    private PcoDefaults() {
    }

    public static void configure(String pcscfV4, String dnsV4) {
        pcscfIpv4 = blankToNull(pcscfV4);
        dnsIpv4 = blankToNull(dnsV4);
    }

    public static String pcscfIpv4() {
        return pcscfIpv4;
    }

    public static String dnsIpv4() {
        return dnsIpv4;
    }

    /** APN factory replacing the old hard-coded {@code 192.0.2.10}. */
    public static ApnConfig named(String apn) {
        return "ims".equalsIgnoreCase(apn)
                ? ApnConfig.ims(pcscfIpv4, dnsIpv4)
                : ApnConfig.internet();
    }

    /** Message-level PCO IE for these defaults; null when nothing configured. */
    public static Gtpv2Ie ie() {
        List<Gtpv2Ies.PcoContainer> containers = containers();
        return containers.isEmpty() ? null : Gtpv2Ies.pco(containers);
    }

    public static List<Gtpv2Ies.PcoContainer> containers() {
        java.util.List<Gtpv2Ies.PcoContainer> out = new java.util.ArrayList<>();
        try {
            if (pcscfIpv4 != null) {
                out.add(Gtpv2Ies.ipv4Container(
                        Gtpv2Ies.PCO_P_CSCF_IPV4, java.net.InetAddress.getByName(pcscfIpv4)));
            }
            if (dnsIpv4 != null) {
                out.add(Gtpv2Ies.ipv4Container(
                        Gtpv2Ies.PCO_DNS_SERVER_IPV4, java.net.InetAddress.getByName(dnsIpv4)));
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("epc.pco.* not an IPv4 literal", e);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
