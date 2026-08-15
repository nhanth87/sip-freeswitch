package et.elisa.ims.hss;

/**
 * O(1) identity candidates (IMPU/IMPI/MSISDN/user-part) — no table scan.
 * Scale-independent: never scan {@code ims_subscriber}; always exact-key candidates.
 */
public final class ImsIdentityKeys {

    private ImsIdentityKeys() {}

    public static String realmOrDefault(String realm) {
        if (realm == null || realm.isBlank()) {
            return "ims.lab";
        }
        return realm.trim();
    }

    /** Bare user-part: {@code alice} from {@code sip:alice@ims.lab} or {@code alice}. */
    public static String userPart(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim();
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            s = s.substring(4);
        }
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }

    public static String impu(String userPart, String realm) {
        if (userPart == null || userPart.isBlank()) {
            return null;
        }
        return "sip:" + userPart.trim() + "@" + realmOrDefault(realm);
    }

    public static String impi(String userPart, String realm) {
        if (userPart == null || userPart.isBlank()) {
            return null;
        }
        return userPart.trim() + "@" + realmOrDefault(realm);
    }

    /**
     * Exact IMPU keys to try for binding / LIR (no table scan).
     * {@code sip:bob@10.0.0.1} and {@code bob} both map to {@code sip:bob@ims.lab}.
     */
    public static java.util.List<String> bindingKeys(String raw) {
        return bindingKeys(raw, null);
    }

    public static java.util.List<String> bindingKeys(String raw, String realm) {
        if (raw == null || raw.isBlank()) {
            return java.util.List.of();
        }
        String r = realmOrDefault(realm);
        String s = raw.trim();
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        keys.add(s);
        String user = userPart(s);
        if (user != null && !user.isBlank()) {
            keys.add(impu(user, r));
            if (!s.regionMatches(true, 0, "sip:", 0, 4)) {
                if (s.indexOf('@') > 0) {
                    keys.add("sip:" + s);
                } else {
                    keys.add(impu(s, r));
                }
            }
        }
        return java.util.List.copyOf(keys);
    }
}
