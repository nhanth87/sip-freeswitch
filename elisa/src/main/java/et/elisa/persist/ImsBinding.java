package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ims_binding")
public class ImsBinding extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String impu;
    @Column(nullable = false, length = 512)
    public String contact;
    @Column(name = "path_header")
    public String pathHeader;
    @Column(name = "home_scscf_node", nullable = false, length = 64)
    public String homeScscfNode;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "call_id")
    public String callId;
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
