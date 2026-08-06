package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_dialplan_rule")
public class SfDialplanRule extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String destination;
    @Column(nullable = false)
    public String action;
    @Column(nullable = false)
    public int priority = 100;
    @Column(nullable = false)
    public boolean enabled = true;
}
