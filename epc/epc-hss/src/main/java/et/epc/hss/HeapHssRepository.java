package et.epc.hss;

import et.epc.api.ApnConfig;
import et.epc.api.Imsi;
import et.epc.api.SubscriberProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/lab only. Production HSS uses {@link JdbcHssRepository}. */
public final class HeapHssRepository implements HssRepository {
    private final ConcurrentHashMap<String, SubscriberRecord> rows = new ConcurrentHashMap<>();
    /** Relational APN profile overlay — heap mirror of {@code epc_apn_profile}. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ApnProfileRow>> apnProfiles =
            new ConcurrentHashMap<>();

    /** One heap APN configuration profile row (mirrors {@code epc_apn_profile}). */
    private record ApnProfileRow(ApnConfig apn, int chargingCharacteristics, boolean defaultApn) {}

    @Override
    public void save(SubscriberRecord rec) {
        rows.put(rec.imsi().digits(), rec);
    }

    @Override
    public Optional<SubscriberRecord> find(Imsi imsi) {
        SubscriberRecord rec = rows.get(imsi.digits());
        return rec == null ? Optional.empty() : Optional.of(withApnProfiles(rec));
    }

    @Override
    public long reserveSqn(Imsi imsi) {
        return row(imsi).reserveSqn();
    }

    @Override
    public void forceSqn(Imsi imsi, long next) {
        row(imsi).forceSqn(next);
    }

    @Override
    public void setServingMme(Imsi imsi, String host) {
        find(imsi).ifPresent(r -> r.setServingMmeHost(host));
    }

    @Override
    public void setPurged(Imsi imsi, boolean purged) {
        find(imsi).ifPresent(r -> r.setPurged(purged));
    }

    @Override
    public void setImsScscf(Imsi imsi, String uri) {
        find(imsi).ifPresent(r -> r.setImsScscfUri(uri));
    }

    @Override
    public Optional<SubscriberRecord> findByImpi(String impi) {
        String norm = norm(impi);
        if (norm.isBlank()) {
            return Optional.empty();
        }
        String user = userPart(norm);
        return rows.values().stream().filter(r -> {
            String rimp = r.impi();
            return (rimp != null && norm.equalsIgnoreCase(rimp))
                    || r.imsi().digits().equals(user);
        }).findFirst();
    }

    @Override
    public Optional<SubscriberRecord> findByImpu(String impu) {
        String norm = stripSip(impu);
        if (norm.isBlank()) {
            return Optional.empty();
        }
        String user = userPart(norm);
        return rows.values().stream().filter(r -> {
            for (String v : r.impus()) {
                if (stripSip(v).equalsIgnoreCase(norm)) {
                    return true;
                }
            }
            String rimp = r.impi();
            if (rimp != null && stripSip(rimp).equalsIgnoreCase(norm)) {
                return true;
            }
            String msisdn = r.profile().msisdn();
            return r.imsi().digits().equals(user)
                    || (msisdn != null && msisdn.equals(user));
        }).findFirst();
    }

    @Override
    public void saveImsIdentity(Imsi imsi, String impi, List<String> impus, String serviceProfileJson) {
        find(imsi).ifPresent(r -> {
            r.setImpi(impi);
            r.setImpus(impus);
            r.setServiceProfileJson(serviceProfileJson);
        });
    }

    @Override
    public void setImpuRegState(Imsi imsi, String state, String scscfUri) {
        find(imsi).ifPresent(r -> {
            r.setImpuRegState(state);
            r.setScscfUri(scscfUri);
        });
    }

    @Override
    public String locateScscf(Imsi imsi) {
        return find(imsi).map(r -> {
            String uri = r.scscfUri();
            if (uri != null && !uri.isBlank()) {
                return uri;
            }
            return r.imsScscfUri();
        }).orElse(null);
    }

    @Override
    public int size() {
        return rows.size();
    }

    @Override
    public List<SubscriberRecord> findAll() {
        List<SubscriberRecord> out = new ArrayList<>(rows.size());
        for (SubscriberRecord rec : rows.values()) {
            out.add(withApnProfiles(rec));
        }
        return out;
    }

    @Override
    public void upsertApnProfile(Imsi imsi, ApnConfig apn, int chargingCharacteristics, boolean defaultApn) {
        apnProfiles.computeIfAbsent(imsi.digits(), k -> new ConcurrentHashMap<>())
                .put(apn.apn(), new ApnProfileRow(apn, chargingCharacteristics, defaultApn));
    }

    @Override
    public void deleteApnProfile(Imsi imsi, String apn) {
        ConcurrentHashMap<String, ApnProfileRow> m = apnProfiles.get(imsi.digits());
        if (m != null) {
            m.remove(apn);
        }
    }

    /**
     * Overlay relational APN profile rows onto the record when present; with no rows the
     * saved profile stays untouched (back-compat with the {@code apns_csv} era).
     */
    private SubscriberRecord withApnProfiles(SubscriberRecord rec) {
        ConcurrentHashMap<String, ApnProfileRow> m = apnProfiles.get(rec.imsi().digits());
        if (m == null || m.isEmpty()) {
            return rec;
        }
        List<ApnProfileRow> ordered = new ArrayList<>(m.values());
        ordered.sort(Comparator.comparing(r -> r.apn().apn()));
        SubscriberProfile p = rec.profile();
        List<ApnConfig> apns = new ArrayList<>(ordered.size());
        String defaultApn = null;
        for (ApnProfileRow r : ordered) {
            apns.add(r.apn());
            if (r.defaultApn() && defaultApn == null) {
                defaultApn = r.apn().apn();
            }
        }
        if (defaultApn == null) {
            defaultApn = p.defaultApn();
        }
        SubscriberProfile np = new SubscriberProfile(p.imsi(), p.msisdn(), p.enabled(), p.homePlmn(),
                apns, defaultApn, p.subscribedQos(), p.chargingCharacteristics());
        SubscriberRecord out = new SubscriberRecord(np, rec.sqn());
        out.setServingMmeHost(rec.servingMmeHost());
        out.setPurged(rec.purged());
        out.setImsScscfUri(rec.imsScscfUri());
        out.setImpi(rec.impi());
        out.setImpus(rec.impus());
        out.setServiceProfileJson(rec.serviceProfileJson());
        return out;
    }

    private SubscriberRecord row(Imsi imsi) {
        SubscriberRecord rec = rows.get(imsi.digits());
        if (rec == null) {
            throw new IllegalArgumentException("unknown IMSI");
        }
        return rec;
    }

    private static String norm(String v) {
        return v == null ? "" : v.trim();
    }

    private static String stripSip(String v) {
        String s = norm(v);
        if (s.regionMatches(true, 0, "sip:", 0, 4)) {
            return s.substring(4);
        }
        return s;
    }

    private static String userPart(String v) {
        String s = stripSip(v);
        int at = s.indexOf('@');
        return at > 0 ? s.substring(0, at) : s;
    }
}
