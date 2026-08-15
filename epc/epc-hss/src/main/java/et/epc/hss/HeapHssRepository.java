package et.epc.hss;

import et.epc.api.Imsi;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/lab only. Production HSS uses {@link JdbcHssRepository}. */
public final class HeapHssRepository implements HssRepository {
    private final ConcurrentHashMap<String, SubscriberRecord> rows = new ConcurrentHashMap<>();

    @Override
    public void save(SubscriberRecord rec) {
        rows.put(rec.imsi().digits(), rec);
    }

    @Override
    public Optional<SubscriberRecord> find(Imsi imsi) {
        return Optional.ofNullable(rows.get(imsi.digits()));
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
    public int size() {
        return rows.size();
    }

    private SubscriberRecord row(Imsi imsi) {
        SubscriberRecord rec = rows.get(imsi.digits());
        if (rec == null) {
            throw new IllegalArgumentException("unknown IMSI");
        }
        return rec;
    }
}
