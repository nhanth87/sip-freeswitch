package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "sf_extension")
public class SfExtension extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(nullable = false)
    public String extension;
    @Column(name = "password_hash")
    public String passwordHash;
    @Column(nullable = false)
    public boolean enabled = true;
}
