package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_queue")
public class SfQueue extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String strategy = "longest-idle-agent";
    @Column(nullable = false)
    public boolean enabled = true;
}
