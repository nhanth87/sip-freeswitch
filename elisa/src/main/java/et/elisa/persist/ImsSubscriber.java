package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ims_subscriber")
public class ImsSubscriber extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true)
    public String impi;
    @Column(nullable = false)
    public String impu;
    @Column(nullable = false, length = 64)
    public String ki;
    @Column(nullable = false, length = 64)
    public String opc;
    @Column(nullable = false, length = 8)
    public String amf = "8000";
    @Column(nullable = false)
    public long sqn;
    @Column(name = "ifc_json")
    public String ifcJson;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
