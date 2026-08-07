package et.elisa.admin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parse FreeSWITCH {@code show channels as delim |} into table rows. */
public final class CallChannelParser {

    private CallChannelParser() {}

    public record ChannelRow(String uuid, String caller, String callee, String state, String callstate, String created) {}

    public static List<ChannelRow> parse(String raw) {
        List<ChannelRow> rows = new ArrayList<>();
        if (raw == null || raw.isBlank() || raw.startsWith("-ERR")) {
            return rows;
        }
        String[] lines = raw.split("\\R");
        if (lines.length == 0) {
            return rows;
        }
        Map<String, Integer> idx = headerIndex(lines[0]);
        int start = idx.isEmpty() ? 0 : 1;
        for (int i = start; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("total")) {
                continue;
            }
            String[] cols = line.split("\\|", -1);
            if (cols.length < 2) {
                continue;
            }
            String uuid = col(cols, idx, "uuid", 0);
            if (uuid.isBlank() || "uuid".equalsIgnoreCase(uuid)) {
                continue;
            }
            String caller = firstNonBlank(
                    col(cols, idx, "cid_num", 7),
                    col(cols, idx, "cid_name", 6),
                    col(cols, idx, "initial_cid_num", -1));
            String callee = firstNonBlank(
                    col(cols, idx, "dest", 9),
                    col(cols, idx, "callee_num", 27),
                    col(cols, idx, "name", 4));
            String state = col(cols, idx, "state", 5);
            String callstate = col(cols, idx, "callstate", 25);
            String created = col(cols, idx, "created", 2);
            rows.add(new ChannelRow(uuid, caller, callee, state, callstate, created));
        }
        return rows;
    }

    private static Map<String, Integer> headerIndex(String headerLine) {
        Map<String, Integer> idx = new LinkedHashMap<>();
        if (headerLine == null || !headerLine.toLowerCase(Locale.ROOT).contains("uuid")) {
            return idx;
        }
        String[] cols = headerLine.split("\\|", -1);
        for (int i = 0; i < cols.length; i++) {
            idx.put(cols[i].trim().toLowerCase(Locale.ROOT), i);
        }
        return idx;
    }

    private static String col(String[] cols, Map<String, Integer> idx, String name, int fallback) {
        Integer i = idx.get(name.toLowerCase(Locale.ROOT));
        int at = i != null ? i : fallback;
        if (at < 0 || at >= cols.length) {
            return "";
        }
        return cols[at] == null ? "" : cols[at].trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }
}
