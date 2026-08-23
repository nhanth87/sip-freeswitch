package et.epc.up.linux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Samples cumulative URR byte counters from an nftables set via
 * {@code nft -j list set <table> <set>} (docs recommend nft; no iptables
 * path). Each set element is a UE IPv4 address carrying a byte counter; the
 * {@link UrrIpIndex} attributes elements to URRs. Dependency-free JSON parse:
 * elements are matched as {@code {"element": "<ip>", ... "bytes": N}} and the
 * native {@code {"elem": {"val": "<ip>", "counter": {... "bytes": N}}}} shape.
 * Soft-fails to an empty sample when the CLI fails.
 */
public final class NftUrrSampler implements UrrCounterSampler {

    public static final String DEFAULT_TABLE = "epc";
    public static final String DEFAULT_SET = "urr_bytes";

    private static final Logger LOG = LogManager.getLogger(NftUrrSampler.class);
    private static final String ELEMENT_KEY = "\"element\"";
    private static final String ELEM_KEY = "\"elem\"";
    private static final String BYTES_KEY = "\"bytes\"";

    private final ProcessRunner runner;
    private final String table;
    private final String setName;
    private final UrrIpIndex index;

    public NftUrrSampler(ProcessRunner runner, String table, String setName, UrrIpIndex index) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.table = Objects.requireNonNull(table, "table");
        this.setName = Objects.requireNonNull(setName, "setName");
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public Map<String, Long> sample() {
        String json;
        try {
            json = runner.run("nft", "-j", "list", "set", table, setName);
        } catch (RuntimeException e) {
            LOG.debug("nft sample failed table={} set={} err={}", table, setName, e.toString());
            return Map.of();
        }
        return parse(json, index);
    }

    /** Dependency-free element parse; visible for tests. */
    static Map<String, Long> parse(String json, UrrIpIndex index) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        int from = 0;
        while (from < json.length()) {
            int elementKey = json.indexOf(ELEMENT_KEY, from);
            int elemKey = json.indexOf(ELEM_KEY, from);
            int key = elementKey < 0 ? elemKey
                    : (elemKey < 0 ? elementKey : Math.min(elementKey, elemKey));
            if (key < 0) {
                break;
            }
            int keyLength = elementKey >= 0 && key == elementKey
                    ? ELEMENT_KEY.length()
                    : ELEM_KEY.length();
            int next = nextElementKey(json, key + keyLength);
            int end = next < 0 ? json.length() : next;

            String ip = firstString(json, key + keyLength, end);
            if ("val".equals(ip)) {
                // native nft shape: "elem": {"val": "<ip>", ...}
                int valKey = json.indexOf("\"val\"", key + keyLength);
                if (valKey >= 0 && valKey < end) {
                    ip = firstString(json, valKey + "\"val\"".length(), end);
                } else {
                    ip = null;
                }
            }
            long bytes = bytesValue(json, key + keyLength, end);
            String urrId = ip == null ? null : index.urrForIp(ip);
            if (urrId != null) {
                out.put(urrId, bytes);
            }
            from = end;
        }
        return out;
    }

    private static int nextElementKey(String json, int from) {
        int elementKey = json.indexOf(ELEMENT_KEY, from);
        int elemKey = json.indexOf(ELEM_KEY, from);
        if (elementKey < 0) {
            return elemKey;
        }
        return elemKey < 0 ? elementKey : Math.min(elementKey, elemKey);
    }

    /** First quoted string strictly inside {@code [from, end)}, or {@code null}. */
    private static String firstString(String json, int from, int end) {
        int open = json.indexOf('"', from);
        if (open < 0 || open >= end) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0 || close > end) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    private static long bytesValue(String json, int from, int end) {
        int key = json.indexOf(BYTES_KEY, from);
        if (key < 0 || key >= end) {
            return 0L;
        }
        int colon = json.indexOf(':', key + BYTES_KEY.length());
        if (colon < 0) {
            return 0L;
        }
        int i = colon + 1;
        while (i < end && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        long value = 0L;
        boolean digit = false;
        while (i < end) {
            char c = json.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
            digit = true;
            i++;
        }
        return digit ? value : 0L;
    }
}
