package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

/** Lab MMTel settings (Homer-lite) — barring / diversion / privacy. */
@Entity
@Table(name = "ims_mmtel_settings")
public class ImsMmtelSettings extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true)
    public String impu;

    @Column(name = "barring_mo", nullable = false)
    public boolean barringMo;

    @Column(name = "barring_mt", nullable = false)
    public boolean barringMt;

    @Column(name = "diversion_uri", length = 512)
    public String diversionUri;

    @Column(name = "privacy_id", nullable = false)
    public boolean privacyId;

    @Column(columnDefinition = "text")
    public String document = "{}";

    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
