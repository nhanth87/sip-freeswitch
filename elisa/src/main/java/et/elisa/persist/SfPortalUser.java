package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sf_portal_user")
public class SfPortalUser extends PanacheEntityBase {
    @Id
    public String username;
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;
    @Column(nullable = false)
    public String role;
    @Column(name = "tenant_id")
    public String tenantId;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(name = "created_at")
    public Instant createdAt;
}
