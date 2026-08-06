package et.elisa.acl;

import et.elisa.persist.SfAclEntry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SIP ingress ACL — default deny when enabled: only approved (allow) CIDRs pass;
 * explicit deny entries win over allow. Hot-path reads from an in-memory snapshot.
 */
@ApplicationScoped
public class SipIngressAclService {

    public static final String LIST_SIP_INGRESS = "sip-ingress";

    private static final Logger LOG = LogManager.getLogger(SipIngressAclService.class);
    private static final Pattern VIA_RECEIVED =
            Pattern.compile(";received=([^;\\s>]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VIA_HOST =
            Pattern.compile("SIP/2\\.0/\\w+\\s+\\[?([^\\]\\s:;]+)\\]?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTACT_HOST =
            Pattern.compile("@\\[?([^\\]\\s:;>]+)\\]?", Pattern.CASE_INSENSITIVE);

    @ConfigProperty(name = "sf.sip.acl.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "sf.sip.acl.default-action", defaultValue = "deny")
    String defaultAction;

    private final CopyOnWriteArrayList<Rule> rules = new CopyOnWriteArrayList<>();

    @PostConstruct
    void init() {
        reload();
    }

    /** Reload allow/deny rules from Postgres into the hot-path snapshot. */
    @Transactional
    public void reload() {
        List<SfAclEntry> rows = SfAclEntry.list("listName = ?1 and enabled = true", LIST_SIP_INGRESS);
        List<Rule> next = new ArrayList<>(rows.size());
        for (SfAclEntry row : rows) {
            try {
                next.add(Rule.parse(row.action, row.cidr));
            } catch (IllegalArgumentException ex) {
                LOG.warn("[sip-acl] skip invalid cidr id={} cidr={}: {}", row.id, row.cidr, ex.getMessage());
            }
        }
        rules.clear();
        rules.addAll(next);
        LOG.info("[sip-acl] reloaded rules={} enabled={} default={}",
                rules.size(), enabled, defaultAction);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String defaultAction() {
        return defaultAction;
    }

    public int ruleCount() {
        return rules.size();
    }

    /**
     * @return true if the SIP source may proceed
     */
    public boolean isAllowed(String peerIp) {
        if (!enabled) {
            return true;
        }
        if (peerIp == null || peerIp.isBlank() || "unknown".equalsIgnoreCase(peerIp)) {
            return !"deny".equalsIgnoreCase(defaultAction);
        }
        String ip = peerIp.trim();
        // Explicit deny first
        for (Rule r : rules) {
            if (r.deny && r.matches(ip)) {
                return false;
            }
        }
        boolean anyAllow = false;
        for (Rule r : rules) {
            if (!r.deny) {
                anyAllow = true;
                if (r.matches(ip)) {
                    return true;
                }
            }
        }
        // Allowlist mode: if any allow rules exist (or default deny), unmatched → deny
        if (anyAllow || "deny".equalsIgnoreCase(defaultAction)) {
            return false;
        }
        return true;
    }

    public static String peerFromViaHeaders(List<String> vias) {
        if (vias == null || vias.isEmpty()) {
            return null;
        }
        String top = vias.getFirst();
        Matcher received = VIA_RECEIVED.matcher(top);
        if (received.find()) {
            return stripBrackets(received.group(1));
        }
        Matcher host = VIA_HOST.matcher(top);
        if (host.find()) {
            return stripBrackets(host.group(1));
        }
        return null;
    }

    public static String peerFromContactUri(String contactUri) {
        if (contactUri == null || contactUri.isBlank()) {
            return null;
        }
        Matcher m = CONTACT_HOST.matcher(contactUri);
        if (m.find()) {
            return stripBrackets(m.group(1));
        }
        // sip:user@host or sip:host
        int at = contactUri.indexOf('@');
        String hostPart = at >= 0 ? contactUri.substring(at + 1) : contactUri.replaceFirst("(?i)^sip:", "");
        int semi = hostPart.indexOf(';');
        if (semi >= 0) {
            hostPart = hostPart.substring(0, semi);
        }
        int colon = hostPart.indexOf(':');
        if (colon >= 0 && !hostPart.contains("]")) {
            hostPart = hostPart.substring(0, colon);
        }
        return stripBrackets(hostPart.trim());
    }

    private static String stripBrackets(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]")) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    public record Rule(boolean deny, InetAddress network, int prefixLen, BigInteger networkBits, int addressBits) {
        public static Rule parse(String action, String cidr) {
            boolean deny = "deny".equalsIgnoreCase(action.trim());
            if (!deny && !"allow".equalsIgnoreCase(action.trim())) {
                throw new IllegalArgumentException("action must be allow or deny");
            }
            String spec = cidr.trim();
            int slash = spec.indexOf('/');
            String host = slash >= 0 ? spec.substring(0, slash) : spec;
            try {
                InetAddress addr = InetAddress.getByName(host);
                byte[] raw = addr.getAddress();
                int max = raw.length * 8;
                int prefix = slash >= 0 ? Integer.parseInt(spec.substring(slash + 1)) : max;
                if (prefix < 0 || prefix > max) {
                    throw new IllegalArgumentException("bad prefix");
                }
                BigInteger bits = new BigInteger(1, raw);
                BigInteger mask = prefix == 0 ? BigInteger.ZERO
                        : BigInteger.ONE.shiftLeft(max).subtract(BigInteger.ONE).shiftRight(max - prefix).shiftLeft(max - prefix);
                return new Rule(deny, addr, prefix, bits.and(mask), max);
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid cidr: " + cidr, e);
            }
        }

        public boolean matches(String ip) {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                if (addr.getAddress().length * 8 != addressBits) {
                    return false;
                }
                BigInteger bits = new BigInteger(1, addr.getAddress());
                BigInteger mask = prefixLen == 0 ? BigInteger.ZERO
                        : BigInteger.ONE.shiftLeft(addressBits).subtract(BigInteger.ONE)
                        .shiftRight(addressBits - prefixLen).shiftLeft(addressBits - prefixLen);
                return bits.and(mask).equals(networkBits);
            } catch (Exception e) {
                return false;
            }
        }
    }
}
