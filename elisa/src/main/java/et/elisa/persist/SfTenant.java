package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sf_tenant")
public class SfTenant extends PanacheEntityBase {
    @Id
    public String id;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(name = "created_at")
    public Instant createdAt;
}
