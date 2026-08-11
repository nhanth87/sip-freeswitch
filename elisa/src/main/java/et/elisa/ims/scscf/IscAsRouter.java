package et.elisa.ims.scscf;

import et.elisa.ims.config.ImsRoleFlags;
import et.elisa.ims.trunk.SipTrunkService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * ISC routing to FreeSWITCH AS/MRF (iFC-lite). Supports multi-AS chain in iFC JSON.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code {"as":"sip:as@fs"}}</li>
 *   <li>{@code {"as":["sip:as1@fs","sip:as2@fs"]}}</li>
 *   <li>{@code {"chain":[{"as":"sip:as1"},{"as":"sip:as2"}]}}</li>
 * </ul>
 */
@ApplicationScoped
public class IscAsRouter {

    private static final Pattern AS_URI = Pattern.compile(
            "\"as\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern AS_ARRAY_ITEM = Pattern.compile(
            "\"(sip:[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    @Inject ImsRoleFlags roles;
    @Inject SipTrunkService trunks;

    @ConfigProperty(name = "sip.trunk.freeswitch-uri", defaultValue = "sip:1000@127.0.0.1:5062")
    String fallbackTrunk;

    @ConfigProperty(name = "sf.ims.bgcf.uri", defaultValue = "")
    String bgcfUri;

    /** Default configured AS (FS / trunk). */
    public String asUri() {
        String configured = roles != null ? roles.iscAsUri() : null;
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (trunks != null) {
            try {
                String fromDb = trunks.resolveIscOrMrfUri();
                if (fromDb != null && !fromDb.isBlank()) {
                    return fromDb;
                }
            } catch (RuntimeException ex) {
                // Panache unavailable in unit tests — fall through
            }
        }
        return fallbackTrunk;
    }

    /**
     * Ordered AS chain from iFC. Empty when AS should not be invoked.
     * Blank iFC + MMTel on → single default {@link #asUri()}.
     */
    public List<String> asChain(String ifcJson) {
        if (!shouldInvokeAs(ifcJson)) {
            return List.of();
        }
        List<String> parsed = parseAsChain(ifcJson);
        if (!parsed.isEmpty()) {
            return List.copyOf(parsed);
        }
        return List.of(asUri());
    }

    /** First AS in chain (or default). Null when skip. */
    public String firstAsUri(String ifcJson) {
        List<String> chain = asChain(ifcJson);
        return chain.isEmpty() ? null : chain.getFirst();
    }

    /**
     * Whether to ISC toward AS/MRF. When {@code sf.ims.feature.mmtel=false}, never force AS
     * (route Request-URI). When on: blank iFC = lab default invoke; else token match.
     */
    public boolean shouldInvokeAs(String ifcJson) {
        if (roles != null && !roles.featureMmtel()) {
            return false;
        }
        if (ifcJson == null || ifcJson.isBlank()) {
            return true; // lab default: always ISC to FS
        }
        String s = ifcJson.toLowerCase(Locale.ROOT);
        if (s.contains("\"skip\"") && (s.contains("true") || s.contains(":true"))) {
            return false;
        }
        return ifcJson.contains("\"as\"") || ifcJson.contains("MRF") || ifcJson.contains("mmtel")
                || ifcJson.contains("\"chain\"");
    }

    /** BGCF breakout URI when configured (E.164 / tel:). */
    public String bgcfUri() {
        return bgcfUri == null ? "" : bgcfUri.trim();
    }

    public boolean hasBgcf() {
        return bgcfUri != null && !bgcfUri.isBlank();
    }

    static List<String> parseAsChain(String ifcJson) {
        if (ifcJson == null || ifcJson.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        int arr = indexOfIgnoreCase(ifcJson, "\"as\"");
        if (arr >= 0) {
            int colon = ifcJson.indexOf(':', arr);
            if (colon > 0) {
                int i = colon + 1;
                while (i < ifcJson.length() && Character.isWhitespace(ifcJson.charAt(i))) {
                    i++;
                }
                if (i < ifcJson.length() && ifcJson.charAt(i) == '[') {
                    int end = ifcJson.indexOf(']', i);
                    String body = end > i ? ifcJson.substring(i + 1, end) : ifcJson.substring(i + 1);
                    Matcher m = AS_ARRAY_ITEM.matcher(body);
                    while (m.find()) {
                        out.add(m.group(1));
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            }
        }
        Matcher single = AS_URI.matcher(ifcJson);
        while (single.find()) {
            String u = single.group(1);
            if (!out.contains(u)) {
                out.add(u);
            }
        }
        return out;
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return hay.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }
}
