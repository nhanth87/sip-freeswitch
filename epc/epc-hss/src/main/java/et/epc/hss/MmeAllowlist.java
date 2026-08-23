package et.epc.hss;

import et.epc.api.PlmnId;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Drawbridge for S6a peer trust — closes the Open5GS #4337 gap where any MME could
 * send ULR/AIR with an arbitrary Origin-Host and take over the per-subscriber serving-MME
 * binding (or walk auth vectors). Only requests whose Origin-Host / Origin-Realm /
 * Visited-PLMN-Id match the configured allowlist are honoured.
 *
 * <p>Config is one comma list ({@code epc.hss.mme.allowlist}) plus a master switch
 * ({@code epc.hss.mme.allowlist.enabled}). Token forms:
 * <ul>
 *   <li>{@code mme.host.mno} or {@code host:mme.host.mno} — an allowed Origin-Host (Diameter-Identity);</li>
 *   <li>{@code realm:epc.mnc001.mcc001.3gppnetwork.org} — an allowed Origin-Realm;</li>
 *   <li>{@code plmn:001-01} — an allowed Visited-PLMN (MCC-MNC).</li>
 * </ul>
 * A bare token is treated as an Origin-Host. A request is allowed when <em>any</em> configured
 * category matches. When enabled but the list is empty the guard is <strong>fail-closed</strong>
 * (deny all) so a misconfigured HSS never silently trusts everyone.</p>
 */
public final class MmeAllowlist {

    private final boolean enabled;
    private final Set<String> originHosts;
    private final Set<String> originRealms;
    private final Set<PlmnId> plmns;

    public MmeAllowlist(boolean enabled, Collection<String> entries) {
        this.enabled = enabled;
        Set<String> hosts = new LinkedHashSet<>();
        Set<String> realms = new LinkedHashSet<>();
        Set<PlmnId> plmns = new LinkedHashSet<>();
        if (entries != null) {
            for (String raw : entries) {
                if (raw == null) {
                    continue;
                }
                String e = raw.trim();
                if (e.isEmpty()) {
                    continue;
                }
                if (e.startsWith("realm:")) {
                    realms.add(e.substring("realm:".length()).trim());
                } else if (e.startsWith("plmn:")) {
                    plmns.add(parsePlmn(e.substring("plmn:".length()).trim()));
                } else if (e.startsWith("host:")) {
                    hosts.add(e.substring("host:".length()).trim());
                } else {
                    hosts.add(e);
                }
            }
        }
        this.originHosts = Set.copyOf(hosts);
        this.originRealms = Set.copyOf(realms);
        this.plmns = Set.copyOf(plmns);
    }

    /** Back-compat/zero-config default: guard off so existing deployments trust as before. */
    public static MmeAllowlist disabled() {
        return new MmeAllowlist(false, Set.of());
    }

    /** Parse the common config string form (comma-separated). Blank -> empty list. */
    public static MmeAllowlist parse(boolean enabled, String commaList) {
        if (!enabled) {
            return disabled();
        }
        if (commaList == null || commaList.isBlank()) {
            return new MmeAllowlist(true, Set.of());
        }
        String[] tokens = commaList.split(",");
        return new MmeAllowlist(true, Arrays.asList(tokens));
    }

    public boolean enabled() {
        return enabled;
    }

    public Set<String> originHosts() {
        return originHosts;
    }

    public Set<String> originRealms() {
        return originRealms;
    }

    public Set<PlmnId> plmns() {
        return plmns;
    }

    /**
     * @return {@code true} when the request is trusted. When disabled this is always true;
     * when enabled it holds when any configured category matches the inbound identity, else
     * (including an empty allowlist) it is {@code false}.
     */
    public boolean allows(String originHost, String originRealm, PlmnId visitedPlmn) {
        if (!enabled) {
            return true;
        }
        if (!originHosts.isEmpty() && originHost != null && originHosts.contains(originHost)) {
            return true;
        }
        if (!originRealms.isEmpty() && originRealm != null && originRealms.contains(originRealm)) {
            return true;
        }
        return !plmns.isEmpty() && visitedPlmn != null && plmns.contains(visitedPlmn);
    }

    private static PlmnId parsePlmn(String s) {
        String t = s.trim().replace(" ", "").replace("-", "");
        if (t.length() < 5 || t.length() > 6 || !t.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("plmn allowlist token must be MCC-MNC, got '" + s + "'");
        }
        return new PlmnId(t.substring(0, 3), t.substring(3));
    }
}