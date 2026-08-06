package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_gateway")
public class SfGateway extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String profile = "external";
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(columnDefinition = "text")
    public String config = "{}";
}
