package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sf_cdr")
public class SfCdr extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "call_uuid")
    public String callUuid;
    @Column(columnDefinition = "text", nullable = false)
    public String payload;
    @Column(name = "created_at")
    public Instant createdAt;
}
