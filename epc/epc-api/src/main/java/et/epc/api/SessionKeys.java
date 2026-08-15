package et.epc.api;

import java.util.Objects;

public record SessionKeys(Imsi imsi, String apn, BearerId defaultBearer) {
    public SessionKeys {
        Objects.requireNonNull(imsi);
        Objects.requireNonNull(apn);
        Objects.requireNonNull(defaultBearer);
    }

    public String id() {
        return imsi.digits() + "/" + apn + "/" + defaultBearer.ebi();
    }
}
