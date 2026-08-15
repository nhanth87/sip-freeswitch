package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;

@Entity
@Table(name = "ims_binding")
public class ImsBinding extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String impu;
    @Column(nullable = false, length = 512)
    public String contact;
    @Column(name = "path_header")
    public String pathHeader;
    @Column(name = "home_scscf_node", nullable = false, length = 64)
    public String homeScscfNode;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "call_id")
    public String callId;
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
    /** REGISTER transport source host (RFC 5626 flow). Contact URI stays as registered. */
    @Column(name = "received_host", length = 255)
    public String receivedHost;
    @Column(name = "received_port")
    public Integer receivedPort;
    @Column(name = "received_transport", length = 16)
    public String receivedTransport;

    public boolean hasReceivedFlow() {
        return receivedHost != null && !receivedHost.isBlank();
    }

    /**
     * Next-hop SIP URI for MT INVITE: {@code sip:<user>@receivedHost:receivedPort}.
     * {@code null} when no flow was stored (use Contact as before).
     */
    public String flowNextHopUri() {
        if (!hasReceivedFlow()) {
            return null;
        }
        String user = userPart(contact);
        if (user == null || user.isBlank()) {
            user = userPart(impu);
        }
        if (user == null || user.isBlank()) {
            user = "ue";
        }
        int port = receivedPort == null || receivedPort <= 0 ? 5060 : receivedPort;
        StringBuilder sb = new StringBuilder("sip:")
                .append(user)
                .append('@')
                .append(sipHost(receivedHost))
                .append(':')
                .append(port);
        if (receivedTransport != null && !receivedTransport.isBlank()) {
            sb.append(";transport=").append(receivedTransport.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /** True when the REGISTER source differs from the Contact host/port (NAT / host-LAN). */
    public boolean flowDiffersFromContact() {
        if (!hasReceivedFlow()) {
            return false;
        }
        String contactHost = hostOf(contact);
        if (contactHost == null || contactHost.isBlank()) {
            return true;
        }
        if (!receivedHost.equalsIgnoreCase(contactHost)) {
            return true;
        }
        int cport = portOf(contact, 5060);
        int rport = receivedPort == null || receivedPort <= 0 ? 5060 : receivedPort;
        return cport != rport;
    }

    static String userPart(String sipUri) {
        if (sipUri == null || sipUri.isBlank()) {
            return null;
        }
        String s = sipUri.trim();
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            s = s.substring(4);
        }
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }

    static String hostOf(String sipUri) {
        if (sipUri == null || sipUri.isBlank()) {
            return null;
        }
        String s = sipUri.trim();
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            s = s.substring(4);
        }
        int at = s.indexOf('@');
        String rest = at >= 0 ? s.substring(at + 1) : s;
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            return end > 0 ? rest.substring(1, end) : rest;
        }
        int cut = rest.length();
        int colon = rest.indexOf(':');
        int semi = rest.indexOf(';');
        int slash = rest.indexOf('/');
        if (colon >= 0) {
            cut = Math.min(cut, colon);
        }
        if (semi >= 0) {
            cut = Math.min(cut, semi);
        }
        if (slash >= 0) {
            cut = Math.min(cut, slash);
        }
        return rest.substring(0, cut);
    }

    static int portOf(String sipUri, int defaultPort) {
        if (sipUri == null || sipUri.isBlank()) {
            return defaultPort;
        }
        String s = sipUri.trim();
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            s = s.substring(4);
        }
        int at = s.indexOf('@');
        String rest = at >= 0 ? s.substring(at + 1) : s;
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            if (end < 0) {
                return defaultPort;
            }
            rest = rest.substring(end + 1);
            if (!rest.startsWith(":")) {
                return defaultPort;
            }
            rest = rest.substring(1);
        } else {
            int colon = rest.indexOf(':');
            if (colon < 0) {
                return defaultPort;
            }
            rest = rest.substring(colon + 1);
        }
        int cut = rest.length();
        int semi = rest.indexOf(';');
        int slash = rest.indexOf('/');
        if (semi >= 0) {
            cut = Math.min(cut, semi);
        }
        if (slash >= 0) {
            cut = Math.min(cut, slash);
        }
        try {
            int p = Integer.parseInt(rest.substring(0, cut));
            return p > 0 ? p : defaultPort;
        } catch (NumberFormatException e) {
            return defaultPort;
        }
    }

    static String sipHost(String host) {
        if (host == null) {
            return "";
        }
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            return "[" + host + "]";
        }
        return host;
    }

    /** True when the SIP URI host is an IP literal (MT Contact / flow), not an IMPU domain. */
    public static boolean looksLikeIpHost(String sipUri) {
        String host = hostOf(sipUri);
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        for (String p : parts) {
            try {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
