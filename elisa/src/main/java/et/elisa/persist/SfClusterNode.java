package et.elisa.persist;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "sf_cluster_node")
public class SfClusterNode extends PanacheEntityBase {
    @Id
    public String id;
    @Column(nullable = false, length = 16)
    public String kind;
    @Column(nullable = false)
    public String address;
    @Column(name = "sip_profile")
    public String sipProfile;
    @Column(nullable = false)
    public boolean enabled = true;
    @Column(nullable = false)
    public String meta = "{}";
    @Column(name = "updated_at")
    public Instant updatedAt;
}
