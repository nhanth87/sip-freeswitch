package et.epc.pcef;

import java.util.List;

/** Off-hot-path Gz CDR sink. Production is Postgres; heap list is test only. */
public interface CdrStore {
    void persist(Cdr cdr);

    /** Persist the opening record for a charging session (kind=START). */
    default void start(Cdr cdr) {
        persist(withKind(cdr, Cdr.Kind.START));
    }

    /** Persist a partial record for a still-open charging session (kind=INTERIM). */
    default void interim(Cdr cdr) {
        persist(withKind(cdr, Cdr.Kind.INTERIM));
    }

    List<Cdr> list();

    private static Cdr withKind(Cdr cdr, Cdr.Kind kind) {
        return new Cdr(cdr.chargingId(), cdr.imsi(), cdr.apn(),
                cdr.uplinkOctets(), cdr.downlinkOctets(), cdr.start(), cdr.end(), kind);
    }
}